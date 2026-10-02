package io.github.oatelauser.jauth.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.oatelauser.jauth.app.JauthHubAppApplication;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * sudo 验证页真渲染回归（v1.3 D0）：passkey+sudo 双开（SPEC §5 默认关，测试属性开合法）下
 * {@code GET /selfservice/sudo} 的真视图渲染——200 text/html、无 C0101 错误体、zh 词条齐；
 * 顺带钉死 returnTo 的本站路径校验（SudoController 实际签名 {@code ?returnTo}）：合法本站路径原样
 * 渲染进回跳链接，外站 URL 回退看板（协议相对/绝对外站都不许成为 JS 跳转目标）。
 *
 * @author oatelauser
 */
@SpringBootTest(classes = JauthHubAppApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:jauth-sudo-render-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "jauth-hub.bootstrap.superadmin.username=superadmin",
            "jauth-hub.bootstrap.superadmin.password=super-secret-placeholder",
            "jauth-hub.passkey.enabled=true",
            "jauth-hub.sudo.enabled=true"
        })
class SudoPageRenderingIntegrationTest {

    private static final String SUPERADMIN_USERNAME = "superadmin";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("sudo 页：200 text/html、验证按钮词条齐、returnTo 原样进回跳链接、无 C0101 错误体")
    void sudoPageRendersVerifyButtonWithReturnTo() throws Exception {
        MvcResult result = this.mockMvc
                .perform(get("/selfservice/sudo")
                        .queryParam("returnTo", "/selfservice/pat")
                        .locale(Locale.SIMPLIFIED_CHINESE)
                        .with(user(SUPERADMIN_USERNAME).roles("SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(not(containsString("C0101"))))
                .andReturn();
        assertThat(bodyOf(result)).contains("强验证").contains("使用通行密钥验证").contains("href=\"/selfservice/pat\"");
    }

    @Test
    @DisplayName("returnTo 外站 URL：服务端校验拒绝，回退看板路径（不渲染外站跳转目标）")
    void sudoPageFallsBackToDashboardForForeignReturnTo() throws Exception {
        MvcResult result = this.mockMvc
                .perform(get("/selfservice/sudo")
                        .queryParam("returnTo", "https://evil.example.com/phish")
                        .locale(Locale.SIMPLIFIED_CHINESE)
                        .with(user(SUPERADMIN_USERNAME).roles("SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(not(containsString("C0101"))))
                .andReturn();
        assertThat(bodyOf(result)).contains("/selfservice/apps").doesNotContain("evil.example.com");
    }

    private static String bodyOf(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
