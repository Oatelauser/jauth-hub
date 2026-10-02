package io.github.oatelauser.jauth.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 登录页 JSON 面真链集成（v1.4 B2，jdbc 模式）：GET 状态字段与 ?error 反射、POST 成功（redirectUrl、
 * 会话固定防护 session id 变化、CSRF token 在新 session 存活、后续请求已认证）、POST 失败（错密码/停用
 * 同形 401 A0520 防枚举）、锁门（A0521 且 {@link AuthenticationManager} 零调用——锁门先于认证的直接证据）、
 * CSRF 契约（无 X-CSRF-Token 头 403，B1 契约原值通过）。
 *
 * <p><b>事件源证明</b>（派单义务）：审计 login.failed 落库（jauth_audit_event 行，TARGET_ID=用户名、
 * DETAIL 含异常因）与锁定生效共同证明——SecurityEventAuditBridge 只认 AuthenticationSuccessEvent/
 * AbstractAuthenticationFailureEvent，而 JSON 桥未做任何人工 publishEvent，事件必来自全局 ProviderManager
 * 的自动发布（JwtLoginService 类注释的 javap 论证）。
 *
 * <p>AuthenticationManager 以计数代理注册（@ConditionalOnMissingBean 让位机制）：既验证真管理器路径
 * （DaoAuthenticationProvider 反映容器 UserDetailsService），又为锁门测试提供"零调用"断言面。
 *
 * @author oatelauser
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, classes = JauthHubStarterTestApplication.class)
@AutoConfigureMockMvc
@Import(LoginApiIntegrationTest.LoginApiConfig.class)
@TestPropertySource(
        properties = {
            "spring.autoconfigure.exclude=io.github.oatelauser.springplus.web.autoconfigure.ExceptionHandlingAutoConfiguration,"
                    + "io.github.oatelauser.springplus.web.autoconfigure.SpringPlusWebAutoConfiguration",
            "spring.datasource.url=jdbc:h2:mem:jauth-login-api-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "jauth-hub.storage=jdbc",
            "jauth-hub.rate-limit.login-max-failures=3",
            "jauth-hub.clients[0].client-id=login-api-client",
            "jauth-hub.clients[0].client-name=Login API Client",
            "jauth-hub.clients[0].client-secret=login-api-secret",
            "jauth-hub.clients[0].grant-types[0]=authorization_code",
            "jauth-hub.clients[0].redirect-uris[0]=https://example.com/cb",
            "jauth-hub.clients[0].scopes[0]=openid"
        })
class LoginApiIntegrationTest {

    /** 测试占位口令（非真实凭据）。 */
    private static final String ALICE_CREDENTIAL_PLACEHOLDER = "placeholder-credential-not-real";

    /** lockme 用户的占位口令（锁定场景专用，与 alice 隔离防计数串扰）。 */
    private static final String LOCKME_CREDENTIAL_PLACEHOLDER = "placeholder-lockme-credential";

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    /** authenticate() 调用计数（锁门"零调用"断言面；每测试上下文一枚）。 */
    static final AtomicInteger AUTHENTICATION_CALLS = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("GET 状态：四字段齐 + permitAll（未认证可访问）；?error 反射 true")
    void loginStateFields() throws Exception {
        this.mockMvc
                .perform(get("/api/login").accept(JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.educational").isBoolean())
                .andExpect(jsonPath("$.data.passkeyEnabled").isBoolean())
                .andExpect(jsonPath("$.data.error").value(false))
                .andExpect(jsonPath("$.data.csrfToken").isNotEmpty())
                .andExpect(jsonPath("$.data.csrfHeaderName").value("X-CSRF-TOKEN"));
        this.mockMvc
                .perform(get("/api/login").accept(JSON).queryParam("error"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.error").value(true));
    }

    @Test
    @DisplayName("POST 成功：200 redirectUrl=/（无 saved request）；session id 变化；CSRF token 新 session 存活；后续请求已认证")
    void postLoginSuccessReplicatesFilterSemantics() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String csrfToken = fetchCsrfToken(session);
        String sessionIdBefore = session.getId();

        this.mockMvc
                .perform(post("/api/login")
                        .session(session)
                        .contentType(JSON)
                        .header("X-CSRF-Token", csrfToken)
                        .content(loginJson("alice", ALICE_CREDENTIAL_PLACEHOLDER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.redirectUrl").value("/"));

        // 会话固定防护：changeSessionId 已发生
        assertThat(session.getId()).isNotEqualTo(sessionIdBefore);
        // CSRF token 在新 session 重发可用（框架 formLogin 同款语义：登录后 CsrfAuthenticationStrategy 换发，
        // 旧 token 随会话固定作废）——取新值打一发受 CSRF 保护的 POST，过 CsrfFilter（401 A0520 = 已过 CSRF、落凭据错）
        String renewedToken = fetchCsrfToken(session);
        assertThat(renewedToken).isNotBlank();
        this.mockMvc
                .perform(post("/api/login")
                        .session(session)
                        .contentType(JSON)
                        .header("X-CSRF-Token", renewedToken)
                        .content(loginJson("alice", "wrong-credential-placeholder")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A0520"));
        // 后续请求以该 session 已认证（B1 状态面 authenticated 口径）
        this.mockMvc
                .perform(get("/api/device/verify").session(session).accept(JSON))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST 失败：错密码 401 A0520；审计 login.failed 落库（事件源证明：无人工 publish 而行在册）")
    void postWrongPasswordFailsWithA0520AndAudit() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String csrfToken = fetchCsrfToken(session);

        this.mockMvc
                .perform(post("/api/login")
                        .session(session)
                        .contentType(JSON)
                        .header("X-CSRF-Token", csrfToken)
                        .content(loginJson("alice", "wrong-credential-placeholder")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A0520"));

        Map<String, Object> failed = this.jdbcTemplate.queryForMap(
                "SELECT * FROM jauth_audit_event WHERE event_type = 'login.failed' ORDER BY ts DESC, id DESC LIMIT 1");
        assertThat(failed.get("TARGET_ID")).isEqualTo("alice");
        assertThat(String.valueOf(failed.get("DETAIL"))).contains("BadCredentials");
    }

    @Test
    @DisplayName("POST 停用用户：与错密码同形 401 A0520（防用户名枚举）")
    void postDisabledUserSameShapeAsWrongPassword() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String csrfToken = fetchCsrfToken(session);

        this.mockMvc
                .perform(post("/api/login")
                        .session(session)
                        .contentType(JSON)
                        .header("X-CSRF-Token", csrfToken)
                        .content(loginJson("bob", ALICE_CREDENTIAL_PLACEHOLDER)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A0520"));
    }

    @Test
    @DisplayName("锁门：连错达阈值后（计数经事件自增）正确口令也 401 A0521，且 authenticate 零调用（锁门先于认证）")
    void lockoutGatesBeforeAuthentication() throws Exception {
        MockHttpSession session = new MockHttpSession();
        for (int i = 0; i < 3; i++) {
            this.mockMvc
                    .perform(post("/api/login")
                            .session(session)
                            .contentType(JSON)
                            .header("X-CSRF-Token", fetchCsrfToken(session))
                            .content(loginJson("lockme", "wrong-credential-placeholder")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("A0520"));
        }

        int callsBeforeLockedAttempt = AUTHENTICATION_CALLS.get();
        this.mockMvc
                .perform(post("/api/login")
                        .session(session)
                        .contentType(JSON)
                        .header("X-CSRF-Token", fetchCsrfToken(session))
                        .content(loginJson("lockme", LOCKME_CREDENTIAL_PLACEHOLDER)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A0521"));
        // 直接证据：锁定期内的请求根本没到 AuthenticationManager（口令校验未触达）
        assertThat(AUTHENTICATION_CALLS.get()).isEqualTo(callsBeforeLockedAttempt);
    }

    @Test
    @DisplayName("CSRF 契约：无 X-CSRF-Token 头的 POST 被拒 403；带 B1 契约原始 token 通过（落 401 A0520 凭据错）")
    void csrfContractOnJsonPost() throws Exception {
        this.mockMvc
                .perform(post("/api/login")
                        .session(new MockHttpSession())
                        .contentType(JSON)
                        .content(loginJson("alice", ALICE_CREDENTIAL_PLACEHOLDER)))
                .andExpect(status().isForbidden());

        MockHttpSession session = new MockHttpSession();
        this.mockMvc
                .perform(post("/api/login")
                        .session(session)
                        .contentType(JSON)
                        .header("X-CSRF-Token", fetchCsrfToken(session))
                        .content(loginJson("alice", "wrong-credential-placeholder")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A0520"));
    }

    // ------------------------------------------------------------------ 小件

    /** 从 GET /api/login 状态面取回 csrfToken 原值（CsrfFilter 惰性请求属性 → JSON 字段）。 */
    private String fetchCsrfToken(MockHttpSession session) throws Exception {
        MvcResult result = this.mockMvc
                .perform(get("/api/login").session(session).accept(JSON))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.csrfToken");
    }

    private static String loginJson(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }

    /**
     * 测试件：UserDetailsService（真实 DaoAuthenticationProvider 路径：alice 活跃 / bob 停用 / lockme 锁定
     * 场景专用）+ authenticate 计数代理（经让位机制成为容器唯一 AuthenticationManager，零调用断言面）。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class LoginApiConfig {

        @Bean
        UserDetailsService loginApiUserDetailsService(PasswordEncoder encoder) {
            return new InMemoryUserDetailsManager(
                    User.withUsername("alice")
                            .password(encoder.encode(ALICE_CREDENTIAL_PLACEHOLDER))
                            .roles("USER")
                            .build(),
                    User.withUsername("bob")
                            .password(encoder.encode(ALICE_CREDENTIAL_PLACEHOLDER))
                            .disabled(true)
                            .roles("USER")
                            .build(),
                    User.withUsername("lockme")
                            .password(encoder.encode(LOCKME_CREDENTIAL_PLACEHOLDER))
                            .roles("USER")
                            .build());
        }

        @Bean
        JdbcTemplate loginApiJdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        /**
         * 计数代理：包住测试自组装的 Dao ProviderManager（同 starter 装配形——上下文感知事件发布器照挂），
         * 让 starter 的同名 bean 让位；authenticate 计数为锁门"零调用"断言面。
         */
        @Bean
        AuthenticationManager countingAuthenticationManager(
                org.springframework.context.ApplicationContext applicationContext,
                UserDetailsService userDetailsService,
                PasswordEncoder passwordEncoder) {
            org.springframework.security.authentication.dao.DaoAuthenticationProvider provider =
                    new org.springframework.security.authentication.dao.DaoAuthenticationProvider(userDetailsService);
            provider.setPasswordEncoder(passwordEncoder);
            org.springframework.security.authentication.ProviderManager delegate =
                    new org.springframework.security.authentication.ProviderManager(provider);
            delegate.setAuthenticationEventPublisher(
                    new org.springframework.security.authentication.DefaultAuthenticationEventPublisher(
                            applicationContext));
            return authentication -> {
                AUTHENTICATION_CALLS.incrementAndGet();
                return delegate.authenticate(authentication);
            };
        }
    }
}
