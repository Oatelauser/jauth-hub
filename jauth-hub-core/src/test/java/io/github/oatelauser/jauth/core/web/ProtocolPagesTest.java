package io.github.oatelauser.jauth.core.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

/**
 * 协议三页路由断言（v1.5 B5b SSR 拆除后重写）：GET 无条件 302 到 {@code /front/<路由>} 的 SPA 皮，
 * 查询串经 {@code HttpServletRequest#getQueryString()} 逐字透传（URI 重载构造请求保编码与顺序，
 * 空查询串不加尾缀 {@code ?}）。页面内容面（教学开关/scope 目录/org 三态）由 JSON 状态面测试钉死
 * （ConsentStateControllerTest 等）。standalone MockMvc 挂 InternalResourceViewResolver——仅由其
 * {@code redirect:} 前缀分支出 RedirectView（无 JSP 转发发生），与 Boot 宿主默认解析器同语义。
 *
 * @author oatelauser
 */
class ProtocolPagesTest {

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                    new LoginController(), new ConsentController(), new DeviceVerifyController())
            .setViewResolvers(new InternalResourceViewResolver())
            .build();

    @Test
    @DisplayName("登录页：无条件 302 到 /front/login；?error 逐字保留")
    void loginRedirectsToFrontWithQueryPreserved() throws Exception {
        this.mockMvc
                .perform(get(URI.create("/login?error")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/front/login?error"));

        this.mockMvc
                .perform(get("/login"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/front/login"));
    }

    @Test
    @DisplayName("consent 页：无条件 302 到 /front/consent；client_id/state/scope/org 逐字保留（含编码）")
    void consentRedirectsToFrontWithQueryPreserved() throws Exception {
        this.mockMvc
                .perform(get(URI.create(
                        "/oauth2/consent?client_id=demo-client&state=st-123&scope=openid%20profile&org=org-1")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        "/front/consent?client_id=demo-client&state=st-123&scope=openid%20profile&org=org-1"));
    }

    @Test
    @DisplayName("设备验证页：无条件 302 到 /front/device-verify；无查询串不加尾缀 ?")
    void deviceVerifyRedirectsToFrontWithoutQuerySuffix() throws Exception {
        this.mockMvc
                .perform(get("/device/verify"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/front/device-verify"));
    }
}
