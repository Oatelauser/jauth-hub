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
 * 登录落点属性化回归（v1.2.1）：{@code jauth-hub.app.home-path} 是两种登录方式（表单/passkey）
 * 唯一的落点配置面——两方式默认目标恒为 {@code /}（用户红线：不许分叉），去向在此单点可配。
 * 本类钉死覆盖语义；默认值（看板）由 {@link RootRedirectIntegrationTest} 钉死。
 *
 * @author oatelauser
 */
@SpringBootTest(classes = JauthHubAppApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:jauth-home-path-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "jauth-hub.bootstrap.superadmin.username=superadmin",
            "jauth-hub.bootstrap.superadmin.password=super-secret-placeholder",
            "jauth-hub.app.home-path=/demo"
        })
class HomePathPropertyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("home-path 覆盖生效：/ → 302 到配置的 /demo")
    void homePathOverrideTakesEffect() throws Exception {
        this.mockMvc
                .perform(get("/").with(user("superadmin").roles("SUPER_ADMIN")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/demo"));
    }
}
