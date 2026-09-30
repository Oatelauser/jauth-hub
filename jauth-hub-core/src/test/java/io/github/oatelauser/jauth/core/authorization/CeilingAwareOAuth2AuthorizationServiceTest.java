package io.github.oatelauser.jauth.core.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.oatelauser.jauth.core.audit.AuditEvent;
import io.github.oatelauser.jauth.core.audit.AuditEventType;
import io.github.oatelauser.jauth.core.client.ClientOwner;
import io.github.oatelauser.jauth.core.client.InMemoryClientOwnerResolver;
import io.github.oatelauser.jauth.core.org.InMemoryInstallationRepository;
import io.github.oatelauser.jauth.core.org.InMemoryOrgRepository;
import io.github.oatelauser.jauth.core.org.Installation;
import io.github.oatelauser.jauth.core.org.InstallationStatus;
import io.github.oatelauser.jauth.core.org.Org;
import io.github.oatelauser.jauth.core.org.OrgMember;
import io.github.oatelauser.jauth.core.org.OrgRole;
import io.github.oatelauser.jauth.core.org.OrgScopeGate;
import io.github.oatelauser.jauth.core.response.JauthErrorCode;
import io.github.oatelauser.jauth.core.response.JauthException;
import io.github.oatelauser.jauth.core.user.InMemoryUserRepository;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.util.UuidV7;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * ceiling 取交强制单测（B9）：组织客户端创建保存剪到请求∩ceiling、个人/平台直通、0 候选与多 org 无选择
 * fail-closed、多 org 会话暂存生效、幂等与带令牌保存直通、审计外层观察到剪后状态（链序证据）。
 *
 * @author oatelauser
 */
class CeilingAwareOAuth2AuthorizationServiceTest {

    private static final String ALICE_ID = "018f0000-0000-7000-8000-000000000001";

    private static final String ORG_1 = "018f0000-0000-7000-8000-0000000000a1";

    private static final String ORG_2 = "018f0000-0000-7000-8000-0000000000a2";

    private static final String ORG_CLIENT_ID = "client-org";

    private static final String STASH_STATE = "consent-state-placeholder";

    /** 令牌/码值均为测试占位,非真实凭据。 */
    private static final String CODE_PLACEHOLDER = "code-placeholder-not-real";

    private static final String ACCESS_TOKEN_PLACEHOLDER = "opaque-access-placeholder-not-real";

    private final List<AuditEvent> auditLog = new ArrayList<>();

    private final List<OAuth2Authorization> saved = new ArrayList<>();

    private final InMemoryUserRepository userRepository = new InMemoryUserRepository();

    private final InMemoryOrgRepository orgRepository = new InMemoryOrgRepository();

    private final InMemoryInstallationRepository installationRepository = new InMemoryInstallationRepository();

    private final InMemoryClientOwnerResolver ownerResolver = new InMemoryClientOwnerResolver();

    private final OrgScopeGate orgScopeGate = new OrgScopeGate(userRepository, orgRepository, installationRepository);

    private final OAuth2AuthorizationService recorder = new OAuth2AuthorizationService() {
        @Override
        public void save(OAuth2Authorization authorization) {
            saved.add(authorization);
        }

        @Override
        public void remove(OAuth2Authorization authorization) {}

        @Override
        public @Nullable OAuth2Authorization findById(String id) {
            return null;
        }

        @Override
        public @Nullable OAuth2Authorization findByToken(String token, @Nullable OAuth2TokenType tokenType) {
            return null;
        }
    };

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void orgClientCreationPrunedToCeilingIntersection() {
        seedUser();
        seedMembership(ORG_1, OrgRole.OWNER);
        seedInstallation(ORG_CLIENT_ID, ORG_1, InstallationStatus.APPROVED, Set.of("openid"));
        ownerResolver.put(ORG_CLIENT_ID, ClientOwner.ofOrg(ORG_1));

        service().save(creationAuthorization(Set.of("openid", "profile")));

        assertThat(saved).singleElement().satisfies(authorization -> {
            assertThat(authorization.getAuthorizedScopes()).containsExactly("openid");
            assertThat(authorization.getRegisteredClientId()).isEqualTo(ORG_CLIENT_ID);
        });
    }

    @Test
    void personalPlatformAndUnregisteredOwnersPassThrough() {
        seedUser();
        ownerResolver.put(ORG_CLIENT_ID, ClientOwner.ofUser(ALICE_ID));
        ownerResolver.put("client-platform", ClientOwner.platform());

        OAuth2Authorization personal = creationAuthorization(Set.of("openid", "profile"));
        OAuth2Authorization platform =
                withClient(creationAuthorization(Set.of("openid", "profile")), "client-platform");
        OAuth2Authorization unregistered =
                withClient(creationAuthorization(Set.of("openid", "profile")), "client-unknown");
        CeilingAwareOAuth2AuthorizationService service = service();

        service.save(personal);
        service.save(platform);
        service.save(unregistered);

        assertThat(saved).containsExactly(personal, platform, unregistered);
    }

    @Test
    void zeroApprovedCandidatesFailsClosed() {
        seedUser();
        seedMembership(ORG_1, OrgRole.MEMBER);
        // 只有 PENDING 安装:不在候选集(口径 = APPROVED 才算)
        seedInstallation(ORG_CLIENT_ID, ORG_1, InstallationStatus.PENDING, Set.of("openid"));
        ownerResolver.put(ORG_CLIENT_ID, ClientOwner.ofOrg(ORG_1));

        assertThatThrownBy(() -> service().save(creationAuthorization(Set.of("openid"))))
                .isInstanceOfSatisfying(
                        JauthException.class, ex -> assertThat(ex.getCode()).isEqualTo(JauthErrorCode.A0508.getCode()));
        assertThat(saved).isEmpty();
    }

    @Test
    void multiOrgWithoutSelectionFailsClosed() {
        seedUser();
        seedMembership(ORG_1, OrgRole.MEMBER);
        seedMembership(ORG_2, OrgRole.MEMBER);
        seedInstallation(ORG_CLIENT_ID, ORG_1, InstallationStatus.APPROVED, Set.of("openid"));
        seedInstallation(ORG_CLIENT_ID, ORG_2, InstallationStatus.APPROVED, Set.of("profile"));
        ownerResolver.put(ORG_CLIENT_ID, ClientOwner.ofOrg(ORG_1));

        assertThatThrownBy(() -> service().save(creationAuthorization(Set.of("openid"))))
                .isInstanceOfSatisfying(
                        JauthException.class, ex -> assertThat(ex.getCode()).isEqualTo(JauthErrorCode.A0508.getCode()));
        assertThat(saved).isEmpty();
    }

    @Test
    void multiOrgUsesSessionStashedSelection() {
        seedUser();
        seedMembership(ORG_1, OrgRole.MEMBER);
        seedMembership(ORG_2, OrgRole.MEMBER);
        seedInstallation(ORG_CLIENT_ID, ORG_1, InstallationStatus.APPROVED, Set.of("openid"));
        seedInstallation(ORG_CLIENT_ID, ORG_2, InstallationStatus.APPROVED, Set.of("profile"));
        ownerResolver.put(ORG_CLIENT_ID, ClientOwner.ofOrg(ORG_1));
        stashSelection(ORG_2);

        service().save(creationAuthorization(Set.of("openid", "profile")));

        // 按暂存 org(ORG_2, ceiling=profile)剪:交集只剩 profile
        assertThat(saved).singleElement().satisfies(authorization -> assertThat(authorization.getAuthorizedScopes())
                .containsExactly("profile"));
    }

    @Test
    void withinCeilingPassesThroughUnchanged() {
        seedUser();
        seedMembership(ORG_1, OrgRole.OWNER);
        seedInstallation(ORG_CLIENT_ID, ORG_1, InstallationStatus.APPROVED, Set.of("openid", "profile", "email"));
        ownerResolver.put(ORG_CLIENT_ID, ClientOwner.ofOrg(ORG_1));
        OAuth2Authorization withinCeiling = creationAuthorization(Set.of("openid", "profile"));

        service().save(withinCeiling);

        assertThat(saved).singleElement().isEqualTo(withinCeiling);
    }

    @Test
    void saveWithAccessTokenPassesThroughEvenBeyondCeiling() {
        seedUser();
        seedMembership(ORG_1, OrgRole.OWNER);
        seedInstallation(ORG_CLIENT_ID, ORG_1, InstallationStatus.APPROVED, Set.of("openid"));
        ownerResolver.put(ORG_CLIENT_ID, ClientOwner.ofOrg(ORG_1));
        OAuth2Authorization withAccessToken = OAuth2Authorization.from(
                        creationAuthorization(Set.of("openid", "profile")))
                .accessToken(accessToken(Set.of("openid", "profile")))
                .build();

        service().save(withAccessToken);

        assertThat(saved).singleElement().isEqualTo(withAccessToken);
    }

    @Test
    void auditingOutsideCeilingObservesPrunedState() {
        seedUser();
        seedMembership(ORG_1, OrgRole.OWNER);
        seedInstallation(ORG_CLIENT_ID, ORG_1, InstallationStatus.APPROVED, Set.of("openid"));
        ownerResolver.put(ORG_CLIENT_ID, ClientOwner.ofOrg(ORG_1));
        // 装配链序:Auditing(Ceiling(base))——审计在最外层,base 只见剪后值
        OAuth2AuthorizationService chain = new AuditingOAuth2AuthorizationService(service(), this.auditLog::add, null);

        OAuth2Authorization creation = creationAuthorization(Set.of("openid", "profile"));
        chain.save(creation);
        OAuth2Authorization issued = OAuth2Authorization.from(saved.get(0))
                .accessToken(accessToken(saved.get(0).getAuthorizedScopes()))
                .build();
        chain.save(issued);

        assertThat(saved.get(0).getAuthorizedScopes()).containsExactly("openid");
        assertThat(saved.get(1).getAuthorizedScopes()).containsExactly("openid");
        assertThat(this.auditLog).anySatisfy(event -> {
            assertThat(event.type()).isEqualTo(AuditEventType.TOKEN_ISSUED);
            assertThat(event.targetId()).isEqualTo(saved.get(1).getId());
        });
    }

    // ------------------------------------------------------------------ 装配小件

    private CeilingAwareOAuth2AuthorizationService service() {
        return new CeilingAwareOAuth2AuthorizationService(recorder, ownerResolver, orgScopeGate);
    }

    private static OAuth2Authorization creationAuthorization(Set<String> authorizedScopes) {
        return withClient(baseAuthorization(authorizedScopes), ORG_CLIENT_ID);
    }

    private static OAuth2Authorization baseAuthorization(Set<String> authorizedScopes) {
        return OAuth2Authorization.withRegisteredClient(registeredClient(ORG_CLIENT_ID))
                .id("auth-" + UuidV7.generate())
                .principalName("alice")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(authorizedScopes)
                .token(new OAuth2AuthorizationCode(
                        CODE_PLACEHOLDER, Instant.now(), Instant.now().plusSeconds(300)))
                .build();
    }

    private static OAuth2Authorization withClient(OAuth2Authorization authorization, String registeredClientId) {
        return OAuth2Authorization.withRegisteredClient(registeredClient(registeredClientId))
                .id(authorization.getId())
                .principalName(authorization.getPrincipalName())
                .authorizationGrantType(authorization.getAuthorizationGrantType())
                .authorizedScopes(authorization.getAuthorizedScopes())
                .attributes(attributes -> attributes.putAll(authorization.getAttributes()))
                .token(authorization.getToken(OAuth2AuthorizationCode.class).getToken())
                .build();
    }

    private static OAuth2AccessToken accessToken(Set<String> scopes) {
        return new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                ACCESS_TOKEN_PLACEHOLDER,
                Instant.now(),
                Instant.now().plusSeconds(7200),
                scopes);
    }

    private static RegisteredClient registeredClient(String id) {
        return RegisteredClient.withId(id)
                .clientId(id)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://placeholder.example.com/callback")
                .scope("openid")
                .scope("profile")
                .build();
    }

    private void seedUser() {
        this.userRepository.save(new JauthUser(
                ALICE_ID,
                "alice",
                "encoded-not-a-real-credential",
                null,
                null,
                JauthUser.ROLE_USER,
                JauthUser.STATUS_ACTIVE,
                null,
                Instant.now()));
    }

    private void seedMembership(String orgId, OrgRole role) {
        this.orgRepository.save(new Org(orgId, "org-" + orgId, Instant.now()));
        this.orgRepository.saveMember(new OrgMember(orgId, ALICE_ID, role, Instant.now()));
    }

    private void seedInstallation(
            String registeredClientId, String orgId, InstallationStatus status, Set<String> ceilingScopes) {
        this.installationRepository.save(new Installation(
                UuidV7.generate().toString(),
                registeredClientId,
                orgId,
                status,
                ceilingScopes,
                ALICE_ID,
                ceilingScopes,
                ALICE_ID,
                Instant.now(),
                Instant.now()));
    }

    /** 模拟 consent POST 线程:请求携带 state 参数、会话带暂存选择(ConsentController GET 时写入)。 */
    private void stashSelection(String orgId) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/authorize");
        request.setParameter("state", STASH_STATE);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                CeilingAwareOAuth2AuthorizationService.CONSENT_ORG_SESSION_KEY_PREFIX + STASH_STATE, orgId);
        request.setSession(session);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
