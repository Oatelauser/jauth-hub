package io.github.oatelauser.jauth.selfservice.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.oatelauser.jauth.core.passkey.InMemoryPasskeyCredentialRepository;
import io.github.oatelauser.jauth.core.passkey.JauthUserEntityRepository;
import io.github.oatelauser.jauth.core.response.DefaultResponseRenderer;
import io.github.oatelauser.jauth.core.response.JauthResponseAdvice;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import io.github.oatelauser.jauth.selfservice.support.Providers;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 通行密钥页 JSON 状态面（v1.5 B1c）：契约（00000 + passkeyEnabled + credentials 行 + csrf 对）、行装配
 * 与提取 statics（{@link PasskeyController#credentialViews}）同形同源、降级态（passkey 关 →
 * passkeyEnabled=false + 空列表不 500）、仓储在场守"主体在池"（A0503，同 SSR 页）。认证面归部署方
 * default 链，本测试不设认证面。
 *
 * @author oatelauser
 */
class PasskeyStateControllerTest {

    private static final Instant T0 = Instant.parse("2026-10-02T10:00:00Z");

    private static final String ALICE = "alice";

    private UserRepository users;

    @BeforeEach
    void setUp() {
        this.users = mock(UserRepository.class);
        when(this.users.findByUsername(ALICE)).thenReturn(user());
    }

    @Test
    @DisplayName("契约：00000 + educational/passkeyEnabled + credentials 行（base64url 全值 + 12 字缩略）+ csrf 对")
    void stateReturnsContractFields() throws Exception {
        byte[] credentialId = credentialId();
        String expectedId = new Bytes(credentialId).toBase64UrlString();

        stateApi(credentials())
                .perform(get("/api/selfservice/passkey").principal(principal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.educational").value(true))
                .andExpect(jsonPath("$.data.passkeyEnabled").value(true))
                .andExpect(jsonPath("$.data.credentials[0].credentialId").value(expectedId))
                .andExpect(jsonPath("$.data.credentials[0].credentialIdShort")
                        .value(expectedId.substring(0, PasskeyController.CREDENTIAL_ID_DISPLAY_LENGTH) + "…"))
                .andExpect(jsonPath("$.data.credentials[0].label").value("我的手机"))
                .andExpect(jsonPath("$.data.credentials[0].createdAt").value(T0.toString()))
                .andExpect(jsonPath("$.data.credentials[0].lastUsedAt").value(T0.toString()))
                .andExpect(jsonPath("$.data.csrfToken").value(not(emptyString())))
                .andExpect(jsonPath("$.data.csrfHeaderName").value("X-CSRF-TOKEN"));
    }

    @Test
    @DisplayName("行装配回归：credentials 与提取 statics（PasskeyController.credentialViews）同形同源")
    void credentialsRowsMatchExtractedStatics() throws Exception {
        List<PasskeyController.PasskeyCredentialView> views =
                PasskeyController.credentialViews(credentials().findByUserId(userHandle()));
        assertThat(views).as("合成数据前置：一行凭据").hasSize(1);
        PasskeyController.PasskeyCredentialView view = views.get(0);

        stateApi(credentials())
                .perform(get("/api/selfservice/passkey").principal(principal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.credentials[0].credentialId").value(view.credentialId()))
                .andExpect(jsonPath("$.data.credentials[0].credentialIdShort").value(view.credentialIdShort()))
                .andExpect(jsonPath("$.data.credentials[0].label").value(view.label()))
                .andExpect(jsonPath("$.data.credentials[0].createdAt")
                        .value(view.createdAt().toString()))
                .andExpect(jsonPath("$.data.credentials[0].lastUsedAt")
                        .value(view.lastUsedAt().toString()));
    }

    @Test
    @DisplayName("降级：仓储缺席（passkey 关）passkeyEnabled=false + credentials 空数组，不 500、不触 A0503")
    void degradedStateRendersEmptyCredentials() throws Exception {
        stateApi(null)
                .perform(get("/api/selfservice/passkey"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.educational").value(true))
                .andExpect(jsonPath("$.data.passkeyEnabled").value(false))
                .andExpect(jsonPath("$.data.credentials").isEmpty())
                .andExpect(jsonPath("$.data.csrfToken").value(not(emptyString())));
    }

    @Test
    @DisplayName("仓储在场守门：未认证 A0503（同 SSR 页口径）")
    void supportedStateRequiresPoolPrincipal() throws Exception {
        stateApi(credentials())
                .perform(get("/api/selfservice/passkey"))
                .andExpect(jsonPath("$.code").value("A0503"));
    }

    /** 状态面 standalone（advice 手挂 + CSRF 过滤器，同家族测试惯例）。 */
    private MockMvc stateApi(@Nullable UserCredentialRepository repository) {
        return MockMvcBuilders.standaloneSetup(new PasskeyStateController(
                        Providers.fixed(repository), this.users, EducationalFlag.ON, new DefaultResponseRenderer()))
                .setControllerAdvice(new JauthResponseAdvice(new DefaultResponseRenderer()))
                .addFilters(new CsrfFilter(new HttpSessionCsrfTokenRepository()))
                .build();
    }

    /** 合成凭据仓：一行"我的手机"凭据（user handle = alice 的 jauth 用户 id，编解码走单点）。 */
    private static InMemoryPasskeyCredentialRepository credentials() {
        InMemoryPasskeyCredentialRepository repository = new InMemoryPasskeyCredentialRepository(event -> {});
        repository.save(credentialRecord());
        return repository;
    }

    private static CredentialRecord credentialRecord() {
        return ImmutableCredentialRecord.builder()
                .credentialId(new Bytes(credentialId()))
                .userEntityUserId(userHandle())
                .publicKey(new ImmutablePublicKeyCose(new byte[] {0x01, 0x02, 0x03}))
                .signatureCount(0)
                .uvInitialized(true)
                .backupEligible(false)
                .backupState(false)
                .transports(java.util.Set.of())
                .created(T0)
                .lastUsed(T0)
                .label("我的手机")
                .build();
    }

    private static Bytes userHandle() {
        return JauthUserEntityRepository.userHandle("user-alice");
    }

    /** 16 字节凭据 id：base64url 22 字符，长于 12 字缩略阈值（缩略逻辑两端可验）。 */
    private static byte[] credentialId() {
        byte[] credentialId = new byte[16];
        for (int i = 0; i < credentialId.length; i++) {
            credentialId[i] = (byte) i;
        }
        return credentialId;
    }

    private static Principal principal() {
        return () -> ALICE;
    }

    private static JauthUser user() {
        return new JauthUser(
                "user-alice",
                ALICE,
                "placeholder-password-hash-not-real",
                null,
                null,
                JauthUser.ROLE_USER,
                JauthUser.STATUS_ACTIVE,
                null,
                T0);
    }
}
