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
import io.github.oatelauser.jauth.selfservice.pat.PatService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
 * PAT 页真渲染回归（v1.3 D0）：照 {@link SelfServicePageRenderingIntegrationTest} 的全 context 范式，
 * 把真视图解析链覆盖扩到 {@code /selfservice/pat} 的两态——空列表与持有令牌。渲染失败会被 advice 包成
 * C0101 JSON（v1.2.1 教训：standalone 测试不真渲染模板，此类缺口从未暴露），故每页最低断言
 * 200 + text/html + 无 C0101 + zh 词条。空态用独立新建号断言（与数据态方法无执行顺序耦合），
 * 数据态经 {@link PatService} 域面直接落一枚令牌（照 PatController 既有测试的域面造数口径）。
 *
 * @author oatelauser
 */
@SpringBootTest(classes = JauthHubAppApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:jauth-pat-render-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "jauth-hub.bootstrap.superadmin.username=superadmin",
            "jauth-hub.bootstrap.superadmin.password=super-secret-placeholder"
        })
class PatPageRenderingIntegrationTest {

    private static final String SUPERADMIN_USERNAME = "superadmin";

    /** 测试占位口令（非真实凭据）。 */
    private static final String PLACEHOLDER_CREDENTIAL = "d0-pass-placeholder";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PatService patService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("PAT 页空列表态：200 text/html、含标题与空态词条、无 C0101 错误体")
    void patPageRendersEmptyListState() throws Exception {
        JauthUser freshUser = ensureUser("d0-pat-empty");
        MvcResult result = this.mockMvc
                .perform(get("/selfservice/pat")
                        .locale(Locale.SIMPLIFIED_CHINESE)
                        .with(user(freshUser.username()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(not(containsString("C0101"))))
                .andReturn();
        assertThat(bodyOf(result)).contains("个人访问令牌").contains("还没有令牌");
    }

    @Test
    @DisplayName("PAT 页数据态：持有一枚令牌后列表行渲染名称与吊销按钮")
    void patPageRendersPopulatedList() throws Exception {
        this.patService.create(
                this.userRepository.findByUsername(SUPERADMIN_USERNAME).id(),
                "D0-渲染验证令牌",
                Set.of("openid"),
                Duration.ofDays(90));
        MvcResult result = this.mockMvc
                .perform(get("/selfservice/pat")
                        .locale(Locale.SIMPLIFIED_CHINESE)
                        .with(user(SUPERADMIN_USERNAME).roles("SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(not(containsString("C0101"))))
                .andReturn();
        assertThat(bodyOf(result)).contains("D0-渲染验证令牌").contains("吊销").doesNotContain("还没有令牌");
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
