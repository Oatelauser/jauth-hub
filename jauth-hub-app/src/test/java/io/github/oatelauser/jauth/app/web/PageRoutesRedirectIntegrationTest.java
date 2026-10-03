package io.github.oatelauser.jauth.app.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.oatelauser.jauth.app.JauthHubAppApplication;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * D0 页面路由网（v1.5 B5b 重写，原真渲染网）：SSR 皮退场后全部页面 GET 无条件 302 到
 * {@code /front/<路由>} 的 SPA 皮，查询串经 {@code HttpServletRequest#getQueryString()} 逐字透传
 * （URI 重载构造请求保编码与顺序；page/size、code/state、returnTo 等语义参数随之到达 SPA），
 * 空查询串不加尾缀 {@code ?}。orgId 只是路径段（控制器入口 OWNER 门随 SSR 退场，门语义在 JSON 面）。
 * 页面内容面由各 JSON 状态面/动作面测试与 jauth-hub-front vitest 钉死。
 *
 * @author oatelauser
 */
@SpringBootTest(classes = JauthHubAppApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:jauth-page-routes-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "jauth-hub.bootstrap.superadmin.username=superadmin",
            "jauth-hub.bootstrap.superadmin.password=super-secret-placeholder"
        })
class PageRoutesRedirectIntegrationTest {

    private static final String SUPERADMIN = "superadmin";

    @Autowired
    private MockMvc mockMvc;

    private void assertRedirect(String path, String expectedFrontPath) throws Exception {
        this.mockMvc
                .perform(get(URI.create(path)).with(user(SUPERADMIN).roles("SUPER_ADMIN")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(expectedFrontPath));
    }

    @Test
    @DisplayName("信任面四页：login（匿名可达）302 且 ?error 逐字保留；consent/device-verify/sudo 认证后 302")
    void trustPagesRedirectToFront() throws Exception {
        this.mockMvc
                .perform(get(URI.create("/login?error")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/front/login?error"));
        assertRedirect(
                "/oauth2/consent?client_id=demo-client&state=st-1&scope=openid%20profile",
                "/front/consent?client_id=demo-client&state=st-1&scope=openid%20profile");
        assertRedirect("/device/verify", "/front/device-verify");
        assertRedirect("/selfservice/sudo?returnTo=/selfservice/pat", "/front/sudo?returnTo=/selfservice/pat");
    }

    @Test
    @DisplayName("自助面：看板/PAT/我的应用（含 /new 旧路）/我的组织/通行密钥 302 到对应 SPA 路由")
    void selfServicePagesRedirectToFront() throws Exception {
        assertRedirect("/selfservice/apps", "/front/selfservice/apps");
        assertRedirect("/selfservice/pat", "/front/selfservice/pat");
        assertRedirect("/selfservice/my-apps", "/front/selfservice/my-apps");
        // /new 无独立 SPA 路由（注册是 my-apps 页内对话框，B0 普查决议）
        assertRedirect("/selfservice/my-apps/new", "/front/selfservice/my-apps");
        assertRedirect("/selfservice/my-orgs", "/front/selfservice/my-orgs");
        assertRedirect("/selfservice/passkey", "/front/selfservice/passkey");
    }

    @Test
    @DisplayName("org 族三页：orgId 入目标路径；不存在的 org 同样 302（控制器入口 OWNER 门随 SSR 退场）")
    void orgPagesRedirectWithPathVariable() throws Exception {
        assertRedirect("/selfservice/orgs/org-x/apps", "/front/selfservice/orgs/org-x/apps");
        assertRedirect("/selfservice/orgs/org-x/installations", "/front/selfservice/orgs/org-x/installations");
        assertRedirect("/selfservice/orgs/org-x/members", "/front/selfservice/orgs/org-x/members");
    }

    @Test
    @DisplayName("app 面两页：profile 302；admin/users 302 且分页查询串逐字保留")
    void appPagesRedirectWithQueryPreserved() throws Exception {
        assertRedirect("/profile", "/front/profile");
        assertRedirect("/admin/users?page=2&size=50", "/front/admin/users?page=2&size=50");
    }

    @Test
    @DisplayName("demo 教学区四页：302 到 /front/demo*；callback 的 code/state 查询串逐字保留")
    void demoPagesRedirectWithCallbackQueryPreserved() throws Exception {
        assertRedirect("/demo", "/front/demo");
        assertRedirect("/demo/callback?code=abc&state=st-9", "/front/demo/callback?code=abc&state=st-9");
        assertRedirect("/demo/token", "/front/demo/token");
        assertRedirect("/demo/api-call", "/front/demo/api-call");
    }
}
