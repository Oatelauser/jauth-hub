package io.github.oatelauser.jauth.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.oatelauser.jauth.app.JauthHubAppApplication;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.util.UuidV7;
import io.github.oatelauser.jauth.selfservice.web.OwnedAppService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 我的应用两页真渲染回归（v1.3 D0）：{@code /selfservice/my-apps} 空态/数据态与
 * {@code /selfservice/my-apps/new} 注册表单页——真视图解析链 200 text/html 落地、无 C0101 错误体、
 * zh 词条齐。空态用独立新建号断言（与数据态方法无执行顺序耦合）；数据态经
 * {@link OwnedAppService} 域面直接注册一枚应用（照 MyAppsController 既有测试的域面造数口径）。
 *
 * @author oatelauser
 */
@SpringBootTest(classes = JauthHubAppApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:jauth-myapps-render-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "jauth-hub.bootstrap.superadmin.username=superadmin",
            "jauth-hub.bootstrap.superadmin.password=super-secret-placeholder"
        })
class MyAppsPageRenderingIntegrationTest {

    private static final String SUPERADMIN_USERNAME = "superadmin";

    /** 测试占位口令（非真实凭据）。 */
    private static final String PLACEHOLDER_CREDENTIAL = "d0-pass-placeholder";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OwnedAppService ownedAppService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("我的应用列表空态：200 text/html、含注册入口与空态词条、无 C0101 错误体")
    void myAppsPageRendersEmptyState() throws Exception {
        JauthUser freshUser = ensureUser("d0-myapps-empty");
        MvcResult result = this.mockMvc
                .perform(get("/selfservice/my-apps")
                        .locale(Locale.SIMPLIFIED_CHINESE)
                        .with(user(freshUser.username()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(not(containsString("C0101"))))
                .andReturn();
        assertThat(bodyOf(result)).contains("我的应用").contains("注册新应用").contains("还没有应用");
    }

    @Test
    @DisplayName("注册表单页：200 text/html、最小三件（名称/回调地址/类型）词条齐、无 C0101 错误体")
    void myAppNewPageRendersRegisterForm() throws Exception {
        MvcResult result = this.mockMvc
                .perform(get("/selfservice/my-apps/new")
                        .locale(Locale.SIMPLIFIED_CHINESE)
                        .with(user(SUPERADMIN_USERNAME).roles("SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(not(containsString("C0101"))))
                .andReturn();
        assertThat(bodyOf(result))
                .contains("注册新应用")
                .contains("回调地址")
                .contains("客户端类型")
                .contains("注册应用");
    }

    @Test
    @DisplayName("我的应用列表数据态：注册一枚公开应用后列表行渲染应用名与 app_ 前缀 Client ID")
    void myAppsPageRendersRegisteredApp() throws Exception {
        this.ownedAppService.register(
                this.userRepository.findByUsername(SUPERADMIN_USERNAME).id(),
                "D0-个人小工具",
                Set.of("https://d0.example.com/cb"),
                false);
        MvcResult result = this.mockMvc
                .perform(get("/selfservice/my-apps")
                        .locale(Locale.SIMPLIFIED_CHINESE)
                        .with(user(SUPERADMIN_USERNAME).roles("SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(not(containsString("C0101"))))
                .andReturn();
        assertThat(bodyOf(result))
                .contains("D0-个人小工具")
                .contains("app_")
                .contains("公开")
                .doesNotContain("还没有应用");
    }

    /** 幂等建号（域面直建，照 selfservice E2E 的 ensureUser 惯例）。 */
    private JauthUser ensureUser(String username) {
        JauthUser existing = this.userRepository.findByUsername(username);
        if (existing != null) {
            return existing;
        }
        JauthUser user = new JauthUser(
                UuidV7.generate().toString(),
                username,
                this.passwordEncoder.encode(PLACEHOLDER_CREDENTIAL),
                null,
                null,
                JauthUser.ROLE_USER,
                JauthUser.STATUS_ACTIVE,
                null,
                Instant.now());
        this.userRepository.save(user);
        return user;
    }

    private static String bodyOf(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
