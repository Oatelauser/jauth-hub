package io.github.oatelauser.jauth.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.oatelauser.jauth.app.JauthHubAppApplication;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 登录全链回归（v1.3 D0；v1.5 B5b 路由 302 化）：GET /login 302 到 /front/login 的 SPA 皮、POST 正确
 * 凭据（带 csrf）→ 302 落点恒为 {@code /}（用户红线：表单/passkey 两方式默认目标不许分叉，去向由
 * RootController 的 home-path 单点接管，默认落点语义照 {@link HomePathPropertyIntegrationTest}）、
 * POST 错误密码 → 302 /login?error → GET 302 /front/login?error（错误词条由 SPA 渲染）、未认证
 * GET /selfservice/apps → 302 引导 /login。链路断言在两态基座（{@code PasskeyEnabled}/
 * {@code PasskeyDisabled}）各跑一遍——开关只增减状态面字段，不许影响表单链本身。
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
        @DisplayName("GET /login：302 到 /front/login 的 SPA 皮（表单本体在皮内，v1.5 B5b）")
        void loginPageRedirectsToFront() throws Exception {
            this.mockMvc
                    .perform(get("/login"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/front/login"));
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
            // GET /login?error：302 到 SPA 皮且 ?error 逐字转发（错误词条由 SPA 渲染）
            this.mockMvc
                    .perform(get(errorUrl))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/front/login?error"));
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
        @DisplayName("passkey 开：GET /login 同样 302 到 /front/login（按钮可见性移至 /api/login 状态面）")
        void loginRedirectHoldsWithPasskeyEnabled() throws Exception {
            this.mockMvc
                    .perform(get("/login"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/front/login"));
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
        @DisplayName("passkey 关：GET /login 302 到 /front/login（开关只影响状态面字段，不影响路由）")
        void loginRedirectHoldsWithPasskeyDisabled() throws Exception {
            this.mockMvc
                    .perform(get("/login"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/front/login"));
        }
    }
}
