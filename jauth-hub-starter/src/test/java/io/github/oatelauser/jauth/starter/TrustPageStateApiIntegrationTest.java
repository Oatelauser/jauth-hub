package io.github.oatelauser.jauth.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 信任面三页 JSON 状态 API 的真链集成（v1.4 B1，memory 模式）：
 *
 * <ul>
 * <li><b>CSRF 契约探针（最关键）</b>：GET 状态面取回 csrfToken 原始值后，以 {@code X-CSRF-Token} 头与
 * {@code _csrf} 表单参数两路 POST 受 CSRF 保护的端点（/login）——必须通过 CsrfFilter（默认
 * XorCsrfTokenRequestAttributeHandler，禁改全局 CSRF 配置的前提是原值本身可用；探针红即契约破裂，
 * 见派单铁律）。探针目标选 /login：CSRF 过 → 认证失败 302 /login?error（坏凭据），CSRF 挂 → 403，可区分。
 * <li>consent/设备验证状态面：链认领 + authenticated（未认证 JSON 401）+ 装配字段 + CSRF 字段。
 * </ul>
 *
 * @author oatelauser
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, classes = JauthHubStarterTestApplication.class)
@AutoConfigureMockMvc
@Import(TrustPageStateApiIntegrationTest.StateApiConfig.class)
@TestPropertySource(
        properties = {
            "spring.flyway.enabled=false",
            "spring.autoconfigure.exclude=io.github.oatelauser.springplus.web.autoconfigure.ExceptionHandlingAutoConfiguration,"
                    + "io.github.oatelauser.springplus.web.autoconfigure.SpringPlusWebAutoConfiguration",
            "jauth-hub.clients[0].client-id=demo-client",
            "jauth-hub.clients[0].client-name=Demo Client",
            "jauth-hub.clients[0].client-secret=demo-secret",
            "jauth-hub.clients[0].grant-types[0]=authorization_code",
            "jauth-hub.clients[0].redirect-uris[0]=https://example.com/cb",
            "jauth-hub.clients[0].scopes[0]=openid",
            "jauth-hub.clients[0].scopes[1]=profile"
        })
class TrustPageStateApiIntegrationTest {

    /** 测试占位口令（非真实凭据）。 */
    private static final String ALICE_CREDENTIAL_PLACEHOLDER = "placeholder-credential-not-real";

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("consent 状态面:链认领 + 认证后 200 出装配字段与 CSRF 字段,未认证 JSON 401")
    void consentStateOnRealChain() throws Exception {
        MvcResult result = this.mockMvc
                .perform(get("/api/consent")
                        .session(loginSession())
                        .accept(JSON)
                        .queryParam("client_id", "demo-client")
                        .queryParam("state", "st-b1")
                        .queryParam("scope", "openid profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.clientId").value("demo-client"))
                .andExpect(jsonPath("$.data.state").value("st-b1"))
                .andExpect(jsonPath("$.data.orgGuide").value(false))
                .andExpect(jsonPath("$.data.orgBadge").value(nullValue()))
                .andExpect(jsonPath("$.data.scopes[?(@.name=='openid')]").isNotEmpty())
                .andExpect(jsonPath("$.data.csrfToken").isNotEmpty())
                .andExpect(jsonPath("$.data.csrfHeaderName").value("X-CSRF-TOKEN"))
                .andReturn();
        String csrfToken = JsonPath.read(result.getResponse().getContentAsString(), "$.data.csrfToken");
        assertThat(csrfToken).isNotBlank();

        this.mockMvc
                .perform(get("/api/consent")
                        .accept(JSON)
                        .queryParam("client_id", "demo-client")
                        .queryParam("state", "st-b1")
                        .queryParam("scope", "openid"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("设备验证状态面:educational + CSRF 字段,未认证 JSON 401")
    void deviceVerifyStateOnRealChain() throws Exception {
        this.mockMvc
                .perform(get("/api/device/verify").session(loginSession()).accept(JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.educational").isBoolean())
                .andExpect(jsonPath("$.data.csrfToken").isNotEmpty())
                .andExpect(jsonPath("$.data.csrfHeaderName").value("X-CSRF-TOKEN"));

        this.mockMvc.perform(get("/api/device/verify").accept(JSON)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("CSRF 契约探针:状态面 csrfToken 原值经 X-CSRF-Token 头与 _csrf 表单参数两路 POST 均过 CsrfFilter")
    void csrfTokenFromStateApiPassesBothChannels() throws Exception {
        MockHttpSession session = loginSession();
        String csrfToken = fetchCsrfToken(session);

        // (a) 头路:X-CSRF-Token 携带原值 → CSRF 通过(坏凭据 302 /login?error,而非 403)
        this.mockMvc
                .perform(loginBuilder().session(session).header("X-CSRF-Token", csrfToken))
                .andExpect(status().is3xxRedirection());

        // (b) 表单路:_csrf 参数携带原值 → 同过
        this.mockMvc
                .perform(loginBuilder().session(session).param("_csrf", csrfToken))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("对照组:无 CSRF 的 POST /login 被 403(证明探针两条路径确在验 CSRF 而非空转)")
    void loginWithoutCsrfIsRejected() throws Exception {
        this.mockMvc.perform(loginBuilder()).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ 小件

    /** 表单登录（真实 DaoAuthenticationProvider 路径），产出已认证会话。 */
    private MockHttpSession loginSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        this.mockMvc
                .perform(post("/login")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "alice")
                        .param("password", ALICE_CREDENTIAL_PLACEHOLDER))
                .andExpect(status().is3xxRedirection());
        return session;
    }

    /** 从 consent 状态面取回 csrfToken 原值（CsrfFilter 惰性请求属性 → JSON 字段）。 */
    private String fetchCsrfToken(MockHttpSession session) throws Exception {
        MvcResult result = this.mockMvc
                .perform(get("/api/consent")
                        .session(session)
                        .accept(JSON)
                        .queryParam("client_id", "demo-client")
                        .queryParam("state", "st-csrf")
                        .queryParam("scope", "openid"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.csrfToken");
    }

    /** POST /login 基础请求（坏凭据）：CSRF 通过时落 302 /login?error,CSRF 拒绝时 403。 */
    private static MockHttpServletRequestBuilder loginBuilder() {
        return post("/login")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", "alice")
                .param("password", "wrong-credential");
    }

    /** 宿主契约件：UserDetailsService（真实表单登录）。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class StateApiConfig {

        @Bean
        UserDetailsService stateApiUserDetailsService(PasswordEncoder encoder) {
            return new InMemoryUserDetailsManager(User.withUsername("alice")
                    .password(encoder.encode(ALICE_CREDENTIAL_PLACEHOLDER))
                    .roles("USER")
                    .build());
        }
    }
}
