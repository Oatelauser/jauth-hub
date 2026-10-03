package io.github.oatelauser.jauth.app.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.oatelauser.jauth.app.JauthHubAppApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 根路径落点回归（v1.2.1）：登录成功默认目标 {@code /} 此前无控制器认领且默认链 anyRequest
 * denyAll——登录即 403。钉死：已认证访问 {@code /} 302 到看板；未认证访问走登录引导（401/302
 * 由链入口语义决定，不 403 denyAll）。
 *
 * @author oatelauser
 */
@SpringBootTest(classes = JauthHubAppApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:jauth-root-redirect-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "jauth-hub.bootstrap.superadmin.username=superadmin",
            "jauth-hub.bootstrap.superadmin.password=super-secret-placeholder"
        })
class RootRedirectIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("已认证访问 / → 302 SPA 看板（v1.5 B5b 默认值）；未认证访问 / → 认证引导而非 403")
    void rootRedirectsToDashboard() throws Exception {
        this.mockMvc
                .perform(get("/").with(user("superadmin").roles("SUPER_ADMIN")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/front/selfservice/apps"));
        // 未认证：链入口把浏览器引导去登录页（302），登录后经保存请求回到 / 再跳看板——闭环
        this.mockMvc.perform(get("/")).andExpect(status().is3xxRedirection());
    }
}
