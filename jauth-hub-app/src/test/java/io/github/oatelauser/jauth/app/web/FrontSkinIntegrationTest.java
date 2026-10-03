package io.github.oatelauser.jauth.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.oatelauser.jauth.app.JauthHubAppApplication;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * front 皮肤同域名装配回归（v1.4 B4）：trust-skin=front 下四页 SSR GET 各 302 到 /front/&lt;路由&gt; 且
 * 查询串<b>逐字</b>保留（经 URI 重载构造请求，getQueryString 原样透传，编码与顺序不变）；/front/** 静态
 * 装配（本测试与皮肤旗标无关，同一上下文顺带钉死）——物理文件 200、history 深链回退 index.html、
 * 带扩展名的 miss 照常 404 不回 index。默认 ssr 的零行为变化由既有页面测试全绿自证。
 *
 * <p>v1.5 B5a 起 app 经 jauth-hub-front-dist 依赖携带 /front 静态（compile；默认态空 jar、-Pdist 带真
 * dist）。上述静态断言即 dist jar 形态的既覆盖：夹具位于 test-classes，classpath 解析先于依赖 jar，把
 * 同名 static/front/index.html 完全遮蔽——两态构建下断言语义不变，测试与 npm 构建序解耦（不硬凑新用例）。
 *
 * @author oatelauser
 */
@SpringBootTest(classes = JauthHubAppApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:jauth-front-skin-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "jauth-hub.bootstrap.superadmin.username=superadmin",
            "jauth-hub.bootstrap.superadmin.password=super-secret-placeholder",
            "jauth-hub.trust-skin=front"
        })
class FrontSkinIntegrationTest {

    private static final String SUPERADMIN_USERNAME = "superadmin";

    /** 深链回退断言标记（src/test/resources/static/front/index.html 夹具）。 */
    private static final String FIXTURE_MARKER = "front-skin-fixture-index";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("登录页 302：?error 逐字保留")
    void loginRedirectsToFrontSkinWithQuery() throws Exception {
        this.mockMvc
                .perform(get(URI.create("/login?error")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/front/login?error"));
    }

    @Test
    @DisplayName("consent 页 302（认证后）：client_id/state/scope/org 逐字保留（含编码）")
    void consentRedirectsToFrontSkinWithQueryPreserved() throws Exception {
        this.mockMvc
                .perform(get(URI.create(
                                "/oauth2/consent?client_id=demo-client&state=st-1&scope=openid%20profile&org=org-1"))
                        .with(user(SUPERADMIN_USERNAME).roles("SUPER_ADMIN")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        "/front/consent?client_id=demo-client&state=st-1&scope=openid%20profile&org=org-1"));
    }

    @Test
    @DisplayName("设备验证页 302（认证后）：无查询串不加尾缀 ?")
    void deviceVerifyRedirectsToFrontSkinWithoutQuerySuffix() throws Exception {
        this.mockMvc
                .perform(get("/device/verify").with(user(SUPERADMIN_USERNAME).roles("SUPER_ADMIN")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/front/device-verify"));
    }

    @Test
    @DisplayName("sudo 页 302（认证后）：returnTo 逐字保留")
    void sudoRedirectsToFrontSkinWithReturnTo() throws Exception {
        this.mockMvc
                .perform(get(URI.create("/selfservice/sudo?returnTo=/selfservice/pat"))
                        .with(user(SUPERADMIN_USERNAME).roles("SUPER_ADMIN")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/front/sudo?returnTo=/selfservice/pat"));
    }

    @Test
    @DisplayName("/front/** 静态装配：物理文件 200；深链回退 index；带扩展名 miss 404 不回 index")
    void frontStaticAssetsServeWithHistoryFallback() throws Exception {
        this.mockMvc
                .perform(get("/front/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(FIXTURE_MARKER)));

        // history 深链：/front/login 无物理文件，回退 index 内容（前端路由接管）
        MvcResult deepLink = this.mockMvc
                .perform(get("/front/login"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(deepLink.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains(FIXTURE_MARKER);

        // 带扩展名的 miss 走本应用静态 miss 的既有口径（照常"未命中"，绝不回退 index）：
        // spring-plus 统一异常处理把 NoResourceFoundException 渲染为 HTTP 200 + A0493 资源不存在体
        // （与 /demo 下任何静态 miss 同款），关键断言是响应绝无 index 内容
        MvcResult missingAsset = this.mockMvc
                .perform(get("/front/logo.png"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("A0493")))
                .andReturn();
        assertThat(missingAsset.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain(FIXTURE_MARKER);
    }
}
