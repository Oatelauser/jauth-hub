package io.github.oatelauser.jauth.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.oatelauser.jauth.app.JauthHubAppApplication;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 登录全链真渲染回归（v1.3 D0）：GET /login 真渲染含表单、POST 正确凭据（带 csrf）→ 302 落点恒为
 * {@code /}（用户红线：表单/passkey 两方式默认目标不许分叉，去向由 RootController 的 home-path 单点接管，
 * 默认落点语义照 {@link HomePathPropertyIntegrationTest}）、POST 错误密码 → 302 /login?error → 重渲染
 * 200 含稳定错误词条、未认证 GET /selfservice/apps → 302 引导 /login。链路断言在两态基座
 * （{@code PasskeyEnabled}/{@code PasskeyDisabled}，照 starter 的 TokenIntrospectionRevocation
 * 双 @Nested 结构）各跑一遍，按钮开关态各自断言——开关只增减登录入口，不许影响表单链本身。
 *
 * @author oatelauser
 */
class LoginFlowIntegrationTest {

    /** 占位口令（播种超管用，非真实凭据）。 */
    private static final String SUPERADMIN_USERNAME = "superadmin";

    private static final String SUPERADMIN_PASSWORD = "super-secret-placeholder";

    /** 登录全链共享断言（两开关态各执行一遍：开关只增减 passkey 入口，表单链语义必须同构）。 */
    abstract static class LoginChainSupport {

        @Autowired
        protected MockMvc mockMvc;

        @Test
        @DisplayName("GET /login 真渲染：200 text/html、表单三件齐（action/用户名/密码）、无 C0101 错误体")
        void loginPageRendersRealForm() throws Exception {
            MvcResult result = this.mockMvc
                    .perform(get("/login").locale(Locale.SIMPLIFIED_CHINESE))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                    .andExpect(content().string(not(containsString("C0101"))))
                    .andReturn();
            assertThat(bodyOf(result))
                    .contains("action=\"/login\"")
                    .contains("name=\"username\"")
                    .contains("name=\"password\"");
        }

        @Test
        @DisplayName("POST 正确凭据（带 csrf）→ 302 落点恒为 /（两登录方式共同默认目标，红线）")
        void postCorrectCredentialsLandsAtRoot() throws Exception {
            this.mockMvc
                    .perform(post("/login")
                            .with(csrf())
                            .param("username", SUPERADMIN_USERNAME)
                            .param("password", SUPERADMIN_PASSWORD))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/"));
        }

        @Test
        @DisplayName("POST 错误密码 → 302 /login?error → GET 重渲染 200 含稳定错误词条")
        void postWrongCredentialsRendersErrorState() throws Exception {
            MvcResult failed = this.mockMvc
                    .perform(post("/login")
                            .with(csrf())
                            .param("username", SUPERADMIN_USERNAME)
                            .param("password", "wrong-password-placeholder"))
                    .andExpect(status().is3xxRedirection())
                    .andReturn();
            String errorUrl = failed.getResponse().getHeader("Location");
            assertThat(errorUrl).contains("/login").contains("error");
            MvcResult reloaded = this.mockMvc
                    .perform(get(errorUrl).locale(Locale.SIMPLIFIED_CHINESE))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                    .andExpect(content().string(not(containsString("C0101"))))
                    .andReturn();
            assertThat(bodyOf(reloaded)).contains("用户名或密码错误");
        }

        @Test
        @DisplayName("未认证 GET /selfservice/apps → 302 引导 /login（default 链浏览器入口）")
        void unauthenticatedSelfServiceFunnelsToLogin() throws Exception {
            this.mockMvc
                    .perform(get("/selfservice/apps"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(result -> assertThat(result.getResponse().getHeader("Location"))
                            .contains("/login"));
        }

        protected static String bodyOf(MvcResult result) throws Exception {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        }
    }

    /** passkey 开（测试属性开，合法）：登录页多渲染「使用通行密钥登录」按钮与内联脚本。 */
    @Nested
    @SpringBootTest(classes = JauthHubAppApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
    @AutoConfigureMockMvc
    @TestPropertySource(
            properties = {
                "spring.datasource.url=jdbc:h2:mem:jauth-login-flow-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "jauth-hub.bootstrap.superadmin.username=superadmin",
                "jauth-hub.bootstrap.superadmin.password=super-secret-placeholder",
                "jauth-hub.passkey.enabled=true"
            })
    class PasskeyEnabled extends LoginChainSupport {

        @Test
        @DisplayName("passkey 开：登录页渲染「使用通行密钥登录」按钮")
        void passkeyLoginButtonPresent() throws Exception {
            MvcResult result = this.mockMvc
                    .perform(get("/login").locale(Locale.SIMPLIFIED_CHINESE))
                    .andExpect(status().isOk())
                    .andReturn();
            assertThat(bodyOf(result)).contains("使用通行密钥登录").contains("id=\"passkey-login\"");
        }
    }

    /** passkey 关（repo 默认态，零额外属性）：按钮/脚本整块缺席，默认关零可见变化（SPEC §5）。 */
    @Nested
    @SpringBootTest(classes = JauthHubAppApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
    @AutoConfigureMockMvc
    @TestPropertySource(
            properties = {
                "spring.datasource.url=jdbc:h2:mem:jauth-login-flow-off-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "jauth-hub.bootstrap.superadmin.username=superadmin",
                "jauth-hub.bootstrap.superadmin.password=super-secret-placeholder"
            })
    class PasskeyDisabled extends LoginChainSupport {

        @Test
        @DisplayName("passkey 关（repo 默认）：按钮与脚本整块缺席")
        void passkeyLoginButtonAbsent() throws Exception {
            MvcResult result = this.mockMvc
                    .perform(get("/login").locale(Locale.SIMPLIFIED_CHINESE))
                    .andExpect(status().isOk())
                    .andReturn();
            assertThat(bodyOf(result)).doesNotContain("使用通行密钥登录").doesNotContain("id=\"passkey-login\"");
        }
    }
}
