package io.github.oatelauser.jauth.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.oatelauser.jauth.core.authorization.AuditingOAuth2AuthorizationService;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.util.UuidV7;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 审计事件集成测试（B7 任务 2，jdbc 模式）：各事件源各落一行、字段齐（actor/target/detail + 请求上下文
 * IP/UA）、<b>内省路径零落库</b>（票 07/P5 的负断言）、发令牌计数指标随首签递增。
 *
 * <p>事件源接线面：登录走真实表单（Security 事件桥）；令牌生命周期走授权服务装饰（save/remove）；
 * consent 走 consent 服务装饰（框架 consent 提交不经自有控制器——装饰类注释的落点论证）。
 *
 * @author oatelauser
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, classes = JauthHubStarterTestApplication.class)
@AutoConfigureMockMvc
@Import(AuditIntegrationTest.AuditTestConfig.class)
@TestPropertySource(
        properties = {
            "spring.autoconfigure.exclude=io.github.oatelauser.springplus.web.autoconfigure.ExceptionHandlingAutoConfiguration,"
                    + "io.github.oatelauser.springplus.web.autoconfigure.SpringPlusWebAutoConfiguration",
            "spring.datasource.url=jdbc:h2:mem:jauth-audit-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "jauth-hub.storage=jdbc",
            "jauth-hub.clients[0].client-id=audit-client",
            "jauth-hub.clients[0].client-name=Audit Client",
            "jauth-hub.clients[0].client-secret=audit-secret",
            "jauth-hub.clients[0].grant-types[0]=authorization_code",
            "jauth-hub.clients[0].grant-types[1]=refresh_token",
            "jauth-hub.clients[0].redirect-uris[0]=https://example.com/cb",
            "jauth-hub.clients[0].require-authorization-consent=true",
            "jauth-hub.clients[0].scopes[0]=openid",
            "jauth-hub.clients[0].scopes[1]=profile"
        })
class AuditIntegrationTest {

    private static final String REDIRECT_URI = "https://example.com/cb";

    private static final String CODE_VERIFIER = "placeholder-code-verifier-43-chars-minimum-abcdef";

    /** 测试占位口令（非真实凭据）。 */
    private static final String ALICE_CREDENTIAL_PLACEHOLDER = "placeholder-credential-not-real";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Test
    @DisplayName("登录成功/失败各落一行：actor 反查用户 id，IP/UA 自请求上下文补齐")
    void loginEventsAudited() throws Exception {
        String userId = ensureAliceSeeded();

        this.mockMvc
                .perform(post("/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "alice")
                        .param("password", "wrong-attempt"))
                .andExpect(status().is3xxRedirection());

        this.mockMvc
                .perform(post("/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "alice")
                        .param("password", ALICE_CREDENTIAL_PLACEHOLDER))
                .andExpect(status().is3xxRedirection());

        Map<String, Object> failed = latestEvent("login.failed");
        Map<String, Object> success = latestEvent("login.success");
        assertThat(failed.get("ACTOR_USER_ID")).isEqualTo(userId);
        assertThat(success.get("ACTOR_USER_ID")).isEqualTo(userId);
        assertThat(success.get("TARGET_TYPE")).isEqualTo("user");
        assertThat(success.get("TARGET_ID")).isEqualTo("alice");
        assertThat(String.valueOf(success.get("IP"))).isNotEqualTo("null");
        assertThat(String.valueOf(success.get("USER_AGENT"))).isNotBlank();
        assertThat(String.valueOf(failed.get("DETAIL"))).contains("BadCredentials");
    }

    @Test
    @DisplayName("consent 接受 → token 首签 → RTR 刷新 → 撤销：四类生命周期各落一行，指标按 client 递增")
    void tokenLifecycleEventsAudited() throws Exception {
        ensureAliceSeeded();
        // 指标按 delta 断言：context 为同类多测试方法共享，绝对值含其它方法的签发
        String registeredId =
                this.registeredClientRepository.findByClientId("audit-client").getId();
        double issuedBefore = this.meterRegistry
                .counter(AuditingOAuth2AuthorizationService.TOKENS_ISSUED_METRIC, "client", registeredId)
                .count();

        // 第一轮：consent 确认页出现并勾选 openid 子集（consent.accepted + 首签 token.issued）
        TokenSet first = authorizationCodeFlowTokens();
        assertThat(latestEvent("consent.accepted").get("TARGET_TYPE")).isEqualTo("client");
        assertThat(String.valueOf(latestEvent("consent.accepted").get("DETAIL")))
                .contains("openid");
        Map<String, Object> issued = latestEvent("token.issued");
        assertThat(issued.get("TARGET_TYPE")).isEqualTo("authorization");
        assertThat(String.valueOf(issued.get("DETAIL"))).contains("client=");

        // 第二轮：consent 已存直接 302 code（第二次 token.issued）
        TokenSet second = authorizationCodeFlowTokens();

        // 刷新（token.refreshed）
        String refreshedAccess = refreshTokenForAccess(second.refreshToken());
        assertThat(latestEvent("token.refreshed").get("EVENT_TYPE")).isEqualTo("token.refreshed");

        // 撤销（token.revoked）
        this.mockMvc
                .perform(post("/oauth2/revoke")
                        .with(httpBasic("audit-client", "audit-secret"))
                        .with(csrf())
                        .param("token", refreshedAccess))
                .andExpect(status().isOk());
        assertThat(latestEvent("token.revoked").get("TARGET_TYPE")).isEqualTo("authorization");

        // 发令牌计数指标随首签递增（client 标签 = 注册 id）：两轮授权码流各首签一次
        assertThat(this.meterRegistry
                                .counter(
                                        AuditingOAuth2AuthorizationService.TOKENS_ISSUED_METRIC, "client", registeredId)
                                .count()
                        - issuedBefore)
                .isEqualTo(2.0);
        assertThat(first.accessToken()).isNotNull();
    }

    @Test
    @DisplayName("内省零落库：active 内省前后审计行数不变（票 07/P5）")
    void introspectionWritesNoAudit() throws Exception {
        ensureAliceSeeded();
        String accessToken = authorizationCodeFlowTokens().accessToken();
        long before = totalEventCount();

        this.mockMvc
                .perform(post("/oauth2/introspect")
                        .with(httpBasic("audit-client", "audit-secret"))
                        .with(csrf())
                        .param("token", accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        assertThat(totalEventCount()).as("内省不打审计").isEqualTo(before);
    }

    /** 授权码全流程（PKCE；consent 页出现即勾选 openid 提交）。 */
    private TokenSet authorizationCodeFlowTokens() throws Exception {
        java.net.URI authorizeUri = UriComponentsBuilder.fromPath("/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", "audit-client")
                .queryParam("scope", "openid profile")
                .queryParam("state", "placeholder-state-not-real")
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("code_challenge", s256(CODE_VERIFIER))
                .queryParam("code_challenge_method", "S256")
                .encode()
                .build()
                .toUri();
        MvcResult first = this.mockMvc
                .perform(get(authorizeUri).with(authenticatedAlice()))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String redirect = first.getResponse().getHeader("Location");

        if (redirect.contains("/oauth2/consent")) {
            String state = queryParam(redirect, "state");
            MvcResult consented = this.mockMvc
                    .perform(post("/oauth2/authorize")
                            .with(authenticatedAlice())
                            .with(csrf())
                            .param("client_id", "audit-client")
                            .param("state", state)
                            .param("scope", "openid"))
                    .andExpect(status().is3xxRedirection())
                    .andReturn();
            redirect = consented.getResponse().getHeader("Location");
        }
        String code = queryParam(redirect, "code");
        assertThat(code).as("授权码已随 302 下发").isNotNull();

        String tokenResponse = this.mockMvc
                .perform(post("/oauth2/token")
                        .with(httpBasic("audit-client", "audit-secret"))
                        .with(csrf())
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("code_verifier", CODE_VERIFIER))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return new TokenSet(
                JsonPath.read(tokenResponse, "$.access_token"), JsonPath.read(tokenResponse, "$.refresh_token"));
    }

    private String refreshTokenForAccess(String refreshToken) throws Exception {
        String tokenResponse = this.mockMvc
                .perform(post("/oauth2/token")
                        .with(httpBasic("audit-client", "audit-secret"))
                        .with(csrf())
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", refreshToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(tokenResponse, "$.access_token");
    }

    private Map<String, Object> latestEvent(String eventType) {
        return this.jdbcTemplate.queryForMap(
                "SELECT * FROM jauth_audit_event WHERE event_type = ? ORDER BY ts DESC, id DESC LIMIT 1", eventType);
    }

    private long totalEventCount() {
        Long count = this.jdbcTemplate.queryForObject("SELECT COUNT(*) FROM jauth_audit_event", Long.class);
        return count != null ? count : 0;
    }

    private String ensureAliceSeeded() {
        JauthUser existing = this.userRepository.findByUsername("alice");
        if (existing != null) {
            return existing.id();
        }
        JauthUser alice = new JauthUser(
                UuidV7.generate().toString(),
                "alice",
                this.passwordEncoder.encode(ALICE_CREDENTIAL_PLACEHOLDER),
                null,
                null,
                JauthUser.ROLE_USER,
                JauthUser.STATUS_ACTIVE,
                null,
                Instant.now());
        this.userRepository.save(alice);
        return alice.id();
    }

    private static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor authenticatedAlice() {
        return user("alice")
                .authorities(
                        FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY),
                        new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static String queryParam(String location, String name) {
        if (location == null) {
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

    /** 令牌对（授权码流的返回投影）。 */
    private record TokenSet(String accessToken, String refreshToken) {}

    /** 测试件：UserDetailsService（真实表单登录）+ SimpleMeterRegistry（指标断言面）。 */
    @TestConfiguration
    static class AuditTestConfig {

        @Bean
        UserDetailsService auditUserDetailsService(PasswordEncoder encoder) {
            return new InMemoryUserDetailsManager(User.withUsername("alice")
                    .password(encoder.encode(ALICE_CREDENTIAL_PLACEHOLDER))
                    .roles("USER")
                    .build());
        }

        @Bean
        MeterRegistry simpleMeterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        JdbcTemplate auditJdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }
    }
}
