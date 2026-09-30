package io.github.oatelauser.jauth.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.oatelauser.jauth.core.token.TokenHash;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.util.UuidV7;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 端到端全流程集成测试（B7 任务 6，starter 测试域，H2 jdbc 模式，MockMvc）：种子客户端（公开 PKCE +
 * 机密内省者）→ 表单登录 → authorize（consent 勾选交集）→ code → token（PKCE）→ userinfo + /me →
 * 内省 active → RTR 刷新（旧 refresh 重放烧族）→ 再授权 → revoke → 内省 inactive → PAT 创建 → PAT 内省
 * active（last_used 前进）→ device flow（发起 → 验证页 → 轮询取 token）。
 *
 * <p>PAT 行以 JdbcTemplate 直插（selfservice 种子面另有契约测试；本域验叠加层接线，交付汇报注明此边界）。
 *
 * @author oatelauser
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, classes = JauthHubStarterTestApplication.class)
@AutoConfigureMockMvc
@Import(EndToEndFlowIntegrationTest.EndToEndConfig.class)
@TestPropertySource(
        properties = {
            "spring.autoconfigure.exclude=io.github.oatelauser.springplus.web.autoconfigure.ExceptionHandlingAutoConfiguration,"
                    + "io.github.oatelauser.springplus.web.autoconfigure.SpringPlusWebAutoConfiguration",
            "spring.datasource.url=jdbc:h2:mem:jauth-e2e;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "jauth-hub.storage=jdbc",
            "jauth-hub.clients[0].client-id=demo-public",
            "jauth-hub.clients[0].client-name=Demo Public Client",
            "jauth-hub.clients[0].grant-types[0]=authorization_code",
            "jauth-hub.clients[0].grant-types[1]=refresh_token",
            "jauth-hub.clients[0].redirect-uris[0]=https://example.com/cb",
            "jauth-hub.clients[0].require-authorization-consent=true",
            "jauth-hub.clients[0].scopes[0]=openid",
            "jauth-hub.clients[0].scopes[1]=profile",
            "jauth-hub.clients[1].client-id=e2e-rs",
            "jauth-hub.clients[1].client-name=E2E Introspector",
            "jauth-hub.clients[1].client-secret=e2e-rs-secret",
            "jauth-hub.clients[1].grant-types[0]=client_credentials",
            "jauth-hub.clients[1].redirect-uris[0]=https://example.com/unused",
            "jauth-hub.clients[1].scopes[0]=openid",
            "jauth-hub.clients[2].client-id=e2e-device",
            "jauth-hub.clients[2].client-name=E2E Device Client",
            "jauth-hub.clients[2].client-secret=e2e-device-secret",
            "jauth-hub.clients[2].grant-types[0]=urn:ietf:params:oauth:grant-type:device_code",
            "jauth-hub.clients[2].redirect-uris[0]=https://example.com/unused",
            "jauth-hub.clients[2].scopes[0]=profile",
            "jauth-hub.clients[3].client-id=e2e-confidential",
            "jauth-hub.clients[3].client-name=E2E Confidential Client",
            "jauth-hub.clients[3].client-secret=e2e-confidential-secret",
            "jauth-hub.clients[3].grant-types[0]=authorization_code",
            "jauth-hub.clients[3].grant-types[1]=refresh_token",
            "jauth-hub.clients[3].redirect-uris[0]=https://example.com/cb2",
            "jauth-hub.clients[3].scopes[0]=openid"
        })
class EndToEndFlowIntegrationTest {

    private static final String REDIRECT_URI = "https://example.com/cb";

    private static final String CODE_VERIFIER = "placeholder-code-verifier-43-chars-minimum-abcdef";

    /** 测试占位口令（非真实凭据）。 */
    private static final String ALICE_CREDENTIAL_PLACEHOLDER = "placeholder-credential-not-real";

    /** 已知形状的 PAT 明文（测试自造，非真实签发面）。 */
    private static final String E2E_PAT_TOKEN = "jpat_e2e-test-token-of-known-shape-43-chars-abcdefg";

    private static final String DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("v1.0 全链路：登录→授权（consent 交集）→令牌→userinfo//me→内省→RTR 烧族→撤销→PAT→设备流")
    void fullJourney() throws Exception {
        // -- 0. 种子客户端形状：公开（PKCE，无 secret/none 认证）+ 机密内省者在场
        RegisteredClient publicClient = this.registeredClientRepository.findByClientId("demo-public");
        assertThat(publicClient.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(publicClient.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(this.registeredClientRepository.findByClientId("e2e-rs")).isNotNull();

        // -- 1. 表单登录（真实 DaoAuthenticationProvider 路径），会话延续到后续授权步骤
        ensureAliceSeeded();
        MockHttpSession session = new MockHttpSession();
        this.mockMvc
                .perform(post("/login")
                        .with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "alice")
                        .param("password", ALICE_CREDENTIAL_PLACEHOLDER))
                .andExpect(status().is3xxRedirection());

        // -- 2. authorize：consent 页出现；勾选 openid 子集（发行 = 请求 ∩ 勾选）
        java.net.URI authorizeUri = UriComponentsBuilder.fromPath("/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", "demo-public")
                .queryParam("scope", "openid profile")
                .queryParam("state", "placeholder-state-not-real")
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("code_challenge", s256(CODE_VERIFIER))
                .queryParam("code_challenge_method", "S256")
                .encode()
                .build()
                .toUri();
        MvcResult authorizeFirst = this.mockMvc
                .perform(get(authorizeUri).session(session))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String consentRedirect = authorizeFirst.getResponse().getHeader("Location");
        assertThat(consentRedirect).contains("/oauth2/consent");

        String state = queryParam(consentRedirect, "state");
        MvcResult consented = this.mockMvc
                .perform(post("/oauth2/authorize")
                        .session(session)
                        .with(csrf())
                        .param("client_id", "demo-public")
                        .param("state", state)
                        .param("scope", "openid"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String code = queryParam(consented.getResponse().getHeader("Location"), "code");
        assertThat(code).as("consent 提交后 302 下发授权码").isNotNull();

        // -- 3. token（公开客户端 PKCE，无 client 凭证）
        String tokenJson = tokenResponse(
                null,
                Map.of(
                        "grant_type", "authorization_code",
                        "code", code,
                        "redirect_uri", REDIRECT_URI,
                        "client_id", "demo-public",
                        "code_verifier", CODE_VERIFIER));
        String accessToken = JsonPath.read(tokenJson, "$.access_token");
        // 框架安全策略（SAS OAuth2RefreshTokenGenerator）：公开客户端（NONE 认证）不发 refresh
        // token——OAuth 2.1 对无凭证客户端的既定防线；刷新链路由机密客户端走（下方第 6 步）
        assertThat(tokenJson).doesNotContain("refresh_token");
        String idToken = JsonPath.read(tokenJson, "$.id_token");
        assertThat((String) JsonPath.read(tokenJson, "$.scope")).isEqualTo("openid");
        assertThat(idToken).isNotBlank();

        // -- 4. userinfo + /me（opaque Bearer：链内本进程内省腿）
        String userId = this.userRepository.findByUsername("alice").id();
        this.mockMvc
                .perform(get("/userinfo").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sub").value(userId));
        String meBody = this.mockMvc
                .perform(get("/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sub").value(userId))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.scope").value("openid"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(meBody).as("平台 API 裸 JSON").doesNotContain("code").doesNotContain("message");

        // -- 5. 内省 active（机密客户端凭证）
        assertIntrospection(accessToken, true);

        // -- 6. RTR 刷新（机密客户端，PKCE）：轮转出新对；旧 refresh 重放 → 烧族（新旧 access 一并 inactive）
        String confidentialCode = obtainConfidentialCode(session);
        String confidentialJson = tokenResponse(
                "e2e-confidential:e2e-confidential-secret",
                Map.of(
                        "grant_type", "authorization_code",
                        "code", confidentialCode,
                        "redirect_uri", "https://example.com/cb2",
                        "client_id", "e2e-confidential",
                        "code_verifier", CODE_VERIFIER));
        String confidentialAccess = JsonPath.read(confidentialJson, "$.access_token");
        String refreshToken = JsonPath.read(confidentialJson, "$.refresh_token");
        assertThat(refreshToken).as("机密客户端签发 refresh token").isNotBlank();

        String refreshedJson = tokenResponse(
                "e2e-confidential:e2e-confidential-secret",
                Map.of(
                        "grant_type", "refresh_token",
                        "refresh_token", refreshToken,
                        "client_id", "e2e-confidential"));
        String newAccess = JsonPath.read(refreshedJson, "$.access_token");
        String newRefresh = JsonPath.read(refreshedJson, "$.refresh_token");
        assertThat(newRefresh).isNotEqualTo(refreshToken);

        this.mockMvc
                .perform(post("/oauth2/token")
                        .with(httpBasic("e2e-confidential", "e2e-confidential-secret"))
                        .with(csrf())
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", refreshToken)
                        .param("client_id", "e2e-confidential"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
        assertIntrospection(newAccess, false);
        assertIntrospection(confidentialAccess, false);

        // -- 7. 再授权（机密客户端无确认页直达 code）→ revoke → 内省 inactive
        String survivingCode = obtainConfidentialCode(session);
        String secondJson = tokenResponse(
                "e2e-confidential:e2e-confidential-secret",
                Map.of(
                        "grant_type", "authorization_code",
                        "code", survivingCode,
                        "redirect_uri", "https://example.com/cb2",
                        "client_id", "e2e-confidential",
                        "code_verifier", CODE_VERIFIER));
        String survivingAccess = JsonPath.read(secondJson, "$.access_token");
        assertIntrospection(survivingAccess, true);

        this.mockMvc
                .perform(post("/oauth2/revoke")
                        .with(httpBasic("e2e-confidential", "e2e-confidential-secret"))
                        .with(csrf())
                        .param("token", survivingAccess)
                        .param("client_id", "e2e-confidential"))
                .andExpect(status().isOk());
        assertIntrospection(survivingAccess, false);

        // -- 8. PAT 创建（直插已知哈希行）→ 内省 active + last_used 前进
        insertPatRow(userId);
        this.mockMvc
                .perform(post("/oauth2/introspect")
                        .with(httpBasic("e2e-rs", "e2e-rs-secret"))
                        .with(csrf())
                        .param("token", E2E_PAT_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.sub").value(userId))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.client_id").value("jauth-pat"));
        java.sql.Timestamp lastUsed = new JdbcTemplate(this.dataSource)
                .queryForObject(
                        "SELECT last_used_at FROM jauth_pat WHERE user_id = ?", java.sql.Timestamp.class, userId);
        assertThat(lastUsed).isNotNull();

        // -- 9. device flow：发起（机密客户端）→ 未验证轮询 pending → 验证页提交 → 轮询取 token
        String deviceJson = this.mockMvc
                .perform(post("/oauth2/device_authorization")
                        .with(httpBasic("e2e-device", "e2e-device-secret"))
                        .with(csrf())
                        .param("scope", "profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.device_code").exists())
                .andExpect(jsonPath("$.user_code").exists())
                .andExpect(jsonPath("$.verification_uri").value("http://localhost/device/verify"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String deviceCode = JsonPath.read(deviceJson, "$.device_code");
        String userCode = JsonPath.read(deviceJson, "$.user_code");

        this.mockMvc
                .perform(post("/oauth2/token")
                        .with(httpBasic("e2e-device", "e2e-device-secret"))
                        .with(csrf())
                        .param("grant_type", DEVICE_GRANT)
                        .param("device_code", deviceCode))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("authorization_pending"));

        this.mockMvc.perform(get("/device/verify").session(session)).andExpect(status().isOk());
        // 框架语义：device flow 首次验证必经确认页（与 requireAuthorizationConsent 无关——
        // OAuth2DeviceVerificationAuthenticationProvider 的既定谓词：无覆盖 scope 的存量 consent 即确认）
        MvcResult consentPage = this.mockMvc
                .perform(post("/device/verify").session(session).with(csrf()).param("user_code", userCode))
                .andExpect(status().isOk())
                .andReturn();
        String consentHtml = consentPage.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(consentHtml).contains("Consent required");
        // 确认表单携带框架生成的 state/client_id 隐藏域，提交时须原样回传
        java.util.regex.Matcher stateMatcher = java.util.regex.Pattern.compile("name=\"state\" value=\"([^\"]+)\"")
                .matcher(consentHtml);
        assertThat(stateMatcher.find()).isTrue();
        // 勾选 scope 提交确认 → 302，此后轮询取到令牌
        this.mockMvc
                .perform(post("/device/verify")
                        .session(session)
                        .with(csrf())
                        .param("client_id", "e2e-device")
                        .param("state", stateMatcher.group(1))
                        .param("user_code", userCode)
                        .param("scope", "profile"))
                .andExpect(status().is3xxRedirection());

        this.mockMvc
                .perform(post("/oauth2/token")
                        .with(httpBasic("e2e-device", "e2e-device-secret"))
                        .with(csrf())
                        .param("grant_type", DEVICE_GRANT)
                        .param("device_code", deviceCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").exists())
                .andExpect(jsonPath("$.token_type").value("Bearer"));
    }

    // ------------------------------------------------------------------ 流程小件

    /** 机密客户端的授权码（PKCE，无确认页直发）。 */
    private String obtainConfidentialCode(MockHttpSession session) throws Exception {
        java.net.URI uri = UriComponentsBuilder.fromPath("/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", "e2e-confidential")
                .queryParam("scope", "openid")
                .queryParam("state", "placeholder-state-not-real")
                .queryParam("redirect_uri", "https://example.com/cb2")
                .queryParam("code_challenge", s256(CODE_VERIFIER))
                .queryParam("code_challenge_method", "S256")
                .encode()
                .build()
                .toUri();
        MvcResult result = this.mockMvc
                .perform(get(uri).session(session))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String code = queryParam(result.getResponse().getHeader("Location"), "code");
        assertThat(code).isNotNull();
        return code;
    }

    /** token 端点请求（basic 凭证可空 = 公开客户端走 client_id 参数）。 */
    private String tokenResponse(String basic, Map<String, String> params) throws Exception {
        MockHttpServletRequestBuilder request = post("/oauth2/token").with(csrf());
        if (basic != null) {
            String[] credentials = basic.split(":");
            request = request.with(httpBasic(credentials[0], credentials[1]));
        }
        for (Map.Entry<String, String> entry : params.entrySet()) {
            request = request.param(entry.getKey(), entry.getValue());
        }
        return this.mockMvc
                .perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private void assertIntrospection(String token, boolean active) throws Exception {
        this.mockMvc
                .perform(post("/oauth2/introspect")
                        .with(httpBasic("e2e-rs", "e2e-rs-secret"))
                        .with(csrf())
                        .param("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(active))
                .andExpect(
                        active
                                ? jsonPath("$.sub")
                                        .value(this.userRepository
                                                .findByUsername("alice")
                                                .id())
                                : jsonPath("$.sub").doesNotExist());
    }

    private void insertPatRow(String userId) {
        JdbcTemplate jdbc = new JdbcTemplate(this.dataSource);
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM jauth_pat WHERE token_sha256 = ?",
                Integer.class,
                TokenHash.sha256Hex(E2E_PAT_TOKEN));
        if (rows != null && rows > 0) {
            return;
        }
        jdbc.update(
                "INSERT INTO jauth_pat (id, user_id, token_sha256, token_prefix, scopes,"
                        + " expires_at, last_used_at, status, created_at) VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?)",
                UuidV7.generate().toString(),
                userId,
                TokenHash.sha256Hex(E2E_PAT_TOKEN),
                E2E_PAT_TOKEN.substring(0, 12),
                "openid",
                java.sql.Timestamp.from(Instant.now().plusSeconds(3600)),
                "ACTIVE",
                java.sql.Timestamp.from(Instant.now()));
    }

    private void ensureAliceSeeded() {
        if (this.userRepository.findByUsername("alice") != null) {
            return;
        }
        this.userRepository.save(new JauthUser(
                UuidV7.generate().toString(),
                "alice",
                this.passwordEncoder.encode(ALICE_CREDENTIAL_PLACEHOLDER),
                null,
                null,
                JauthUser.ROLE_USER,
                JauthUser.STATUS_ACTIVE,
                null,
                Instant.now()));
    }

    private static String queryParam(String location, String name) {
        if (location == null || !location.contains("?")) {
            return null;
        }
        return java.util.Arrays.stream(
                        location.substring(location.indexOf('?') + 1).split("&"))
                .map(param -> param.split("=", 2))
                .filter(pair -> pair[0].equals(name))
                .map(pair -> java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8))
                .findFirst()
                .orElse(null);
    }

    private static String s256(String codeVerifier) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable on this JVM", ex);
        }
    }

    /** 宿主契约件：UserDetailsService（真实表单登录）。 */
    @TestConfiguration
    static class EndToEndConfig {

        @Bean
        UserDetailsService e2eUserDetailsService(PasswordEncoder encoder) {
            return new InMemoryUserDetailsManager(User.withUsername("alice")
                    .password(encoder.encode(ALICE_CREDENTIAL_PLACEHOLDER))
                    .roles("USER")
                    .build());
        }
    }
}
