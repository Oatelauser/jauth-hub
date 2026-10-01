package io.github.oatelauser.jauth.core.passkey;

import static org.assertj.core.api.Assertions.assertThat;

import com.webauthn4j.converter.AttestationObjectConverter;
import com.webauthn4j.converter.CollectedClientDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.ResidentKeyRequirement;
import com.webauthn4j.data.UserVerificationRequirement;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.client.ClientDataType;
import com.webauthn4j.data.client.CollectedClientData;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.Challenge;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.test.authenticator.CredentialCreationResponse;
import com.webauthn4j.test.authenticator.CredentialRequestResponse;
import com.webauthn4j.test.authenticator.webauthn.NoneAttestationAuthenticator;
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor;
import io.github.oatelauser.jauth.core.audit.AuditEvent;
import io.github.oatelauser.jauth.core.audit.AuditEventPublisher;
import io.github.oatelauser.jauth.core.audit.AuditEventType;
import io.github.oatelauser.jauth.core.user.InMemoryUserRepository;
import io.github.oatelauser.jauth.core.user.JauthUser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorTransport;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthentication;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthenticationProvider;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthenticationRequestToken;
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialCreationOptionsRequest;
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialRequestOptionsRequest;
import org.springframework.security.web.webauthn.management.ImmutableRelyingPartyRegistrationRequest;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.security.web.webauthn.management.Webauthn4JRelyingPartyOperations;

/**
 * Passkey 注册→认证全回路测试（C1 核心验收）：webauthn4j-test 合成真凭据（none attestation），走 SS7
 * {@link Webauthn4JRelyingPartyOperations} + 我们的存储/用户面适配，断言 principal 形态对齐——
 *
 * <ul>
 * <li>注册成功 → 凭据仓储有行、审计发 passkey.registered（用户句柄 = jauth_user.id）
 * <li>认证成功 → {@link WebAuthnAuthentication}：principal = PublicKeyCredentialUserEntity（非 UserDetails）、
 * authorities = 宿主 UserDetailsService 角色 + FACTOR_WEBAUTHN 因子权威、getName() = 登录名（claims 同键）
 * <li>认证后刷新（signCount/lastUsed）走 save 更新路径 → 不重复发注册事件
 * </ul>
 *
 * @author oatelauser
 */
class PasskeyAuthenticationLoopTest {

    private static final String RP_ID = "localhost";

    private static final String RP_NAME = "jauth-hub-test";

    private static final String ORIGIN = "http://localhost:9000";

    private static final String ALICE_ID = "018f0000-0000-7000-8000-0000000000aa";

    private final List<AuditEvent> auditLog = new ArrayList<>();

    private final AuditEventPublisher auditPublisher = this.auditLog::add;

    private final InMemoryUserRepository users = new InMemoryUserRepository();

    private PublicKeyCredentialUserEntityRepository userEntities;

    private UserCredentialRepository credentials;

    private Webauthn4JRelyingPartyOperations relyingPartyOperations;

    private final ObjectConverter objectConverter = new ObjectConverter();

    private final WebAuthnAuthenticatorAdaptor authenticator =
            new WebAuthnAuthenticatorAdaptor(new NoneAttestationAuthenticator(), this.objectConverter);

    @BeforeEach
    void setUp() {
        this.users.save(new JauthUser(
                ALICE_ID,
                "alice",
                "{bcrypt}placeholder-not-a-real-hash",
                "Alice Display",
                null,
                JauthUser.ROLE_USER,
                JauthUser.STATUS_ACTIVE,
                null,
                Instant.parse("2026-10-01T07:00:00Z")));
        this.userEntities = new JauthUserEntityRepository(this.users);
        this.credentials = new InMemoryPasskeyCredentialRepository(this.auditPublisher);
        this.relyingPartyOperations = new Webauthn4JRelyingPartyOperations(
                this.userEntities,
                this.credentials,
                org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity.builder()
                        .id(RP_ID)
                        .name(RP_NAME)
                        .build(),
                Set.of(ORIGIN));
    }

    @Test
    void registerThenAuthenticateYieldsFactorAwareAuthentication() {
        PublicKeyCredentialCreationOptions creationOptions = createCreationOptions();
        registerCredential(creationOptions);

        assertThat(this.credentials.findByUserId(JauthUserEntityRepository.userHandle(ALICE_ID)))
                .hasSize(1);
        assertThat(eventsOfType(AuditEventType.PASSKEY_REGISTERED)).hasSize(1);
        assertThat(eventsOfType(AuditEventType.PASSKEY_REGISTERED).get(0).actorUserId())
                .isEqualTo(ALICE_ID);

        Authentication authentication = authenticate(createRequestOptions());

        assertThat(authentication).isInstanceOf(WebAuthnAuthentication.class);
        assertThat(authentication.getName()).isEqualTo("alice");
        assertThat(authentication.getPrincipal())
                .isInstanceOf(org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity.class);
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_USER", "FACTOR_WEBAUTHN");
        // 认证成功后的 save 刷新（signCount/lastUsed）不得重复发注册事件
        assertThat(eventsOfType(AuditEventType.PASSKEY_REGISTERED)).hasSize(1);
        assertThat(this.credentials
                        .findByCredentialId(this.credentials
                                .findByUserId(JauthUserEntityRepository.userHandle(ALICE_ID))
                                .get(0)
                                .getCredentialId())
                        .getSignatureCount())
                .isPositive();
    }

    private PublicKeyCredentialCreationOptions createCreationOptions() {
        Authentication alice = UsernamePasswordAuthenticationToken.authenticated(
                "alice", "N/A", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        return this.relyingPartyOperations.createPublicKeyCredentialCreationOptions(
                new ImmutablePublicKeyCredentialCreationOptionsRequest(alice));
    }

    private void registerCredential(PublicKeyCredentialCreationOptions creationOptions) {
        PublicKeyCredential<AuthenticatorAttestationResponse> credential = synthesizeRegistration(creationOptions);
        this.relyingPartyOperations.registerCredential(new ImmutableRelyingPartyRegistrationRequest(
                creationOptions, new RelyingPartyPublicKey(credential, "my-passkey")));
    }

    private Authentication authenticate(PublicKeyCredentialRequestOptions requestOptions) {
        PublicKeyCredential<AuthenticatorAssertionResponse> assertion = synthesizeAssertion(requestOptions);
        UserDetailsService userDetailsService = username -> {
            JauthUser user = this.users.findByUsername(username);
            return User.withUsername(username)
                    .password(user.passwordHash())
                    .roles(user.role())
                    .build();
        };
        WebAuthnAuthenticationProvider provider =
                new WebAuthnAuthenticationProvider(this.relyingPartyOperations, userDetailsService);
        return provider.authenticate(new WebAuthnAuthenticationRequestToken(
                new RelyingPartyAuthenticationRequest(requestOptions, assertion)));
    }

    private PublicKeyCredentialRequestOptions createRequestOptions() {
        Authentication alice = UsernamePasswordAuthenticationToken.authenticated(
                "alice", "N/A", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        return this.relyingPartyOperations.createCredentialRequestOptions(
                new ImmutablePublicKeyCredentialRequestOptionsRequest(alice));
    }

    /** SS 创建选项 → webauthn4j-test 合成 none-attestation 注册响应 → SS PublicKeyCredential。 */
    private PublicKeyCredential<AuthenticatorAttestationResponse> synthesizeRegistration(
            PublicKeyCredentialCreationOptions creationOptions) {
        Challenge challenge =
                new DefaultChallenge(creationOptions.getChallenge().getBytes());
        CollectedClientData clientData =
                new CollectedClientData(ClientDataType.WEBAUTHN_CREATE, challenge, new Origin(ORIGIN), null);
        com.webauthn4j.data.PublicKeyCredentialCreationOptions w4jOptions =
                new com.webauthn4j.data.PublicKeyCredentialCreationOptions(
                        new com.webauthn4j.data.PublicKeyCredentialRpEntity(
                                creationOptions.getRp().getId(),
                                creationOptions.getRp().getName()),
                        new com.webauthn4j.data.PublicKeyCredentialUserEntity(
                                creationOptions.getUser().getId().getBytes(),
                                creationOptions.getUser().getName(),
                                creationOptions.getUser().getDisplayName()),
                        challenge,
                        List.of(new PublicKeyCredentialParameters(
                                com.webauthn4j.data.PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256)),
                        creationOptions.getTimeout().toMillis(),
                        List.of(),
                        new com.webauthn4j.data.AuthenticatorSelectionCriteria(
                                null, ResidentKeyRequirement.REQUIRED, UserVerificationRequirement.PREFERRED),
                        com.webauthn4j.data.AttestationConveyancePreference.NONE,
                        null);
        CredentialCreationResponse response = this.authenticator.register(w4jOptions, clientData);
        byte[] credentialId = response.getAttestationObject()
                .getAuthenticatorData()
                .getAttestedCredentialData()
                .getCredentialId();
        byte[] attestationObject =
                new AttestationObjectConverter(this.objectConverter).convertToBytes(response.getAttestationObject());
        byte[] clientDataJson = new CollectedClientDataConverter(this.objectConverter).convertToBytes(clientData);
        return PublicKeyCredential.<AuthenticatorAttestationResponse>builder()
                .id(new Bytes(credentialId).toBase64UrlString())
                .rawId(new Bytes(credentialId))
                .type(PublicKeyCredentialType.PUBLIC_KEY)
                .response(AuthenticatorAttestationResponse.builder()
                        .attestationObject(new Bytes(attestationObject))
                        .clientDataJSON(new Bytes(clientDataJson))
                        .transports(List.of(AuthenticatorTransport.INTERNAL))
                        .build())
                .build();
    }

    /** SS 请求选项 → webauthn4j-test 合成断言 → SS PublicKeyCredential。 */
    private PublicKeyCredential<AuthenticatorAssertionResponse> synthesizeAssertion(
            PublicKeyCredentialRequestOptions requestOptions) {
        Challenge challenge = new DefaultChallenge(requestOptions.getChallenge().getBytes());
        CollectedClientData clientData =
                new CollectedClientData(ClientDataType.WEBAUTHN_GET, challenge, new Origin(ORIGIN), null);
        List<com.webauthn4j.data.PublicKeyCredentialDescriptor> allowCredentials =
                requestOptions.getAllowCredentials().stream()
                        .map(descriptor -> new com.webauthn4j.data.PublicKeyCredentialDescriptor(
                                com.webauthn4j.data.PublicKeyCredentialType.PUBLIC_KEY,
                                descriptor.getId().getBytes(),
                                null))
                        .toList();
        com.webauthn4j.data.PublicKeyCredentialRequestOptions w4jRequestOptions =
                new com.webauthn4j.data.PublicKeyCredentialRequestOptions(
                        challenge,
                        requestOptions.getTimeout().toMillis(),
                        requestOptions.getRpId(),
                        allowCredentials,
                        UserVerificationRequirement.PREFERRED,
                        null);
        CredentialRequestResponse response = this.authenticator.authenticate(w4jRequestOptions, clientData);
        return PublicKeyCredential.<AuthenticatorAssertionResponse>builder()
                .id(new Bytes(response.getCredentialId()).toBase64UrlString())
                .rawId(new Bytes(response.getCredentialId()))
                .type(PublicKeyCredentialType.PUBLIC_KEY)
                .response(AuthenticatorAssertionResponse.builder()
                        .clientDataJSON(new Bytes(response.getCollectedClientDataBytes()))
                        .authenticatorData(new Bytes(response.getAuthenticatorDataBytes()))
                        .signature(new Bytes(response.getSignature()))
                        .userHandle(response.getUserHandle() == null ? null : new Bytes(response.getUserHandle()))
                        .build())
                .build();
    }

    private List<AuditEvent> eventsOfType(AuditEventType type) {
        return this.auditLog.stream().filter(event -> event.type() == type).toList();
    }
}
