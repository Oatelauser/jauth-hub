package io.github.oatelauser.jauth.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.oatelauser.jauth.app.JauthHubAppApplication;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 自助页真渲染回归（v1.2.1）：selfservice 自造引擎的 {@code SpringResourceTemplateResolver} 非容器
 * bean、Thymeleaf 3.1 起引擎不再传导 ApplicationContext——真渲染必炸 "Application Context cannot be
 * null"，而既有页面测试全是 standaloneSetup（不真渲染模板），从未暴露。本测试走全 context + 真视图
 * 解析链钉死该缺口：渲染失败（advice 回 C0101 JSON）即红。passkey 同开顺带断言看板导航入口。
 * 断言中文文案钉 zh（B12 教训：CI en 环境防 locale 回退污染）。
 *
 * @author oatelauser
 */
@SpringBootTest(classes = JauthHubAppApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:jauth-selfservice-render-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "jauth-hub.bootstrap.superadmin.username=superadmin",
            "jauth-hub.bootstrap.superadmin.password=super-secret-placeholder",
            "jauth-hub.passkey.enabled=true",
            "jauth-hub.sudo.enabled=true"
        })
class SelfServicePageRenderingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("看板页经真视图链渲染：text/html、含 passkey 导航词条、无 C0101 错误体")
    void appsDashboardRendersThroughRealViewResolution() throws Exception {
        MvcResult result = this.mockMvc
                .perform(get("/selfservice/apps")
                        .locale(Locale.SIMPLIFIED_CHINESE)
                        .with(user("superadmin").roles("SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.TEXT_HTML))
                .andExpect(content().string(not(containsString("C0101"))))
                .andReturn();
        // 真渲染的 HTML 才含导航词条（错误 JSON 或 standalone 视图名断言都不含）
        assertThat(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("通行密钥");
    }
}
