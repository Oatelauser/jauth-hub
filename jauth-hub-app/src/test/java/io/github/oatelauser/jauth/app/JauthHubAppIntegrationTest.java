package io.github.oatelauser.jauth.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.oatelauser.jauth.app.bootstrap.SuperAdminProperties;
import io.github.oatelauser.jauth.app.bootstrap.SuperAdminSeeder;
import io.github.oatelauser.jauth.core.response.ResponseRenderer;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.springplus.web.response.SimpleResponse;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 壳层集成测试（H2 mem，PostgreSQL 兼容模式）：context 启动 → 超管 seeding 幂等、demo 客户端已播、协议/教学
 * 页面可达、资源服务器端点无 token 401、{@code @RequiresRole} 两态、spring-plus 桥渲染形状。
 *
 * @author oatelauser
 */
@SpringBootTest(classes = JauthHubAppApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:jauth-app-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "jauth-hub.bootstrap.superadmin.username=superadmin",
            "jauth-hub.bootstrap.superadmin.password=super-secret-placeholder"
        })
class JauthHubAppIntegrationTest {

    private static final String SUPERADMIN_USERNAME = "superadmin";

    private static final String SUPERADMIN_PASSWORD = "super-secret-placeholder";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ResponseRenderer responseRenderer;

    @Autowired
    private SuperAdminProperties superAdminProperties;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("首启超管：context 启动即已建号，重复播种幂等不增行不改密")
    void superAdminSeededIdempotent() {
        JauthUser seeded = this.userRepository.findByUsername(SUPERADMIN_USERNAME);
        assertThat(seeded).as("启动 seeding 已建超管").isNotNull();
        assertThat(seeded.role()).isEqualTo(JauthUser.ROLE_SUPERADMIN);
        assertThat(this.passwordEncoder.matches(SUPERADMIN_PASSWORD, seeded.passwordHash()))
                .as("DelegatingPasswordEncoder 编码的密码可校验")
                .isTrue();

        String originalId = seeded.id();
        new SuperAdminSeeder(this.superAdminProperties, this.userRepository, this.passwordEncoder).seed();

        JdbcTemplate jdbcTemplate = new JdbcTemplate(this.dataSource);
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM jauth_user WHERE username = ?", Integer.class, SUPERADMIN_USERNAME);
        assertThat(rows).isEqualTo(1);
        assertThat(this.userRepository.findByUsername(SUPERADMIN_USERNAME).id())
                .as("存在即跳过：不覆盖原账号")
                .isEqualTo(originalId);
    }

    @Test
    @DisplayName("demo 客户端种子：公开 PKCE 客户端与内省机密客户端均已播（yml 播种面）")
    void demoClientsSeeded() {
        RegisteredClient publicClient = this.registeredClientRepository.findByClientId("demo-public");
        assertThat(publicClient).as("demo-public 已播").isNotNull();
        assertThat(publicClient.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(publicClient.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(publicClient.getClientSettings().isRequireAuthorizationConsent())
                .as("教学第 2 步：consent 页可见（B6-fix 缺陷 3）")
                .isTrue();
        // v1.5 B4：白名单新旧两条（旧 SSR 回调 + SPA 回调），缺一即授权码流程换端断链
        assertThat(publicClient.getRedirectUris())
                .contains("http://localhost:8080/demo/callback", "http://localhost:8080/front/demo/callback");
        assertThat(publicClient.getScopes()).containsExactlyInAnyOrder("openid", "profile");

        RegisteredClient rsClient = this.registeredClientRepository.findByClientId("demo-rs");
        assertThat(rsClient).as("demo-rs 已播").isNotNull();
        assertThat(rsClient.getClientSecret()).isNotBlank();
        assertThat(rsClient.getClientAuthenticationMethods()).contains(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
    }

    @Test
    @DisplayName("登录页与 /demo 四页 200：协议链出页面、教学区经 default 链 permitAll")
    void pagesReachable() throws Exception {
        this.mockMvc.perform(get("/login")).andExpect(status().isOk());
        this.mockMvc.perform(get("/demo")).andExpect(status().isOk());
        this.mockMvc.perform(get("/demo/callback")).andExpect(status().isOk());
        this.mockMvc.perform(get("/demo/token")).andExpect(status().isOk());
        this.mockMvc.perform(get("/demo/api-call")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("表单登录走自持用户域：正确密码 302 成功、错误密码 302 回登录页")
    void formLoginAgainstOwnUserStore() throws Exception {
        this.mockMvc
                .perform(post("/login")
                        .with(csrf())
                        .param("username", SUPERADMIN_USERNAME)
                        .param("password", SUPERADMIN_PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(result ->
                        assertThat(result.getResponse().getHeader("Location")).doesNotContain("error"));
        this.mockMvc
                .perform(post("/login")
                        .with(csrf())
                        .param("username", SUPERADMIN_USERNAME)
                        .param("password", "wrong-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(result ->
                        assertThat(result.getResponse().getHeader("Location")).contains("error"));
    }

    @Test
    @DisplayName("/api/demo/whoami 无 token 401（rs-starter 资源服务器语义）")
    void whoamiWithoutTokenIs401() throws Exception {
        this.mockMvc.perform(get("/api/demo/whoami")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("@RequiresRole 两态：SUPER_ADMIN 200 且 SimpleResponse 形状、普通 USER 403")
    void adminEndpointRoleStates() throws Exception {
        this.mockMvc
                .perform(get("/api/admin/summary").with(user("superadmin").roles("SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.operator").value("superadmin"));
        this.mockMvc
                .perform(get("/api/admin/summary").with(user("plain-user").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("spring-plus 桥自动生效：ResponseRenderer 产出 SimpleResponse（classpath 有 spring-plus-web）")
    void simpleResponseBridgeActive() {
        assertThat(this.responseRenderer.renderSuccess("payload")).isInstanceOf(SimpleResponse.class);
        assertThat(((SimpleResponse<?>) this.responseRenderer.renderSuccess("payload")).getCode())
                .isEqualTo("00000");
    }
}
