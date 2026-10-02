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
 * 通行密钥页真渲染回归（v1.3 D0）：passkey 开启（SPEC §5 默认关，测试属性开合法）下
 * {@code /selfservice/passkey} 的空凭据列表态——真视图解析链 200 text/html 落地、无 C0101 错误体、
 * zh 词条齐（标题/添加表单/空态）。数据态不在此造：注册需浏览器 WebAuthn ceremony，
 * 空列表是页面合法且必须不炸的常态（D0 基线）。
 *
 * @author oatelauser
 */
@SpringBootTest(classes = JauthHubAppApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:jauth-passkey-render-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "jauth-hub.bootstrap.superadmin.username=superadmin",
            "jauth-hub.bootstrap.superadmin.password=super-secret-placeholder",
            "jauth-hub.passkey.enabled=true"
        })
class PasskeyPageRenderingIntegrationTest {

    private static final String SUPERADMIN_USERNAME = "superadmin";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("通行密钥页空列表态：200 text/html、注册表单与空态词条齐、无 C0101 错误体")
    void passkeyPageRendersEmptyCredentialList() throws Exception {
        MvcResult result = this.mockMvc
                .perform(get("/selfservice/passkey")
                        .locale(Locale.SIMPLIFIED_CHINESE)
                        .with(user(SUPERADMIN_USERNAME).roles("SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(not(containsString("C0101"))))
                .andReturn();
        assertThat(bodyOf(result)).contains("通行密钥").contains("添加通行密钥").contains("还没有通行密钥");
    }

    private static String bodyOf(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
