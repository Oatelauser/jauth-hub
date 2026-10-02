package io.github.oatelauser.jauth.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.oatelauser.jauth.app.JauthHubAppApplication;
import io.github.oatelauser.jauth.core.org.InstallationService;
import io.github.oatelauser.jauth.core.org.Org;
import io.github.oatelauser.jauth.core.org.OrgService;
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
 * 组织三页真渲染回归（v1.3 D0）：{@code /selfservice/my-orgs}、{@code /selfservice/orgs/{orgId}/apps}、
 * {@code /selfservice/orgs/{orgId}/installations}——fixtures 照 selfservice E2E
 * （OrgAppInstallationFlowIntegrationTest）的域面造数口径：OWNER 建号建 org、registerOrg 注册 org 应用、
 * InstallationService.request 落一条 PENDING 待批。每方法自建独立命名的 org（无方法间执行顺序耦合）。
 * 负路径按控制器实际语义断言：不存在的 orgId 先撞 OWNER 门（isOwner=false → A0508），
 * 家族惯例 HTTP 200 语义在 code 段——非 403/404。
 *
 * @author oatelauser
 */
@SpringBootTest(classes = JauthHubAppApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:jauth-orgs-render-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "jauth-hub.bootstrap.superadmin.username=superadmin",
            "jauth-hub.bootstrap.superadmin.password=super-secret-placeholder"
        })
class OrgPagesRenderingIntegrationTest {

    private static final String SUPERADMIN_USERNAME = "superadmin";

    /** 安装发起人（域内普通用户；发起不要求 org 成员，控制点在 OWNER 审批）。 */
    private static final String REQUESTER_USERNAME = "d0-org-requester";

    /** 测试占位口令（非真实凭据）。 */
    private static final String PLACEHOLDER_CREDENTIAL = "d0-pass-placeholder";

    private static final String REDIRECT_URI = "https://d0.example.com/cb";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrgService orgService;

    @Autowired
    private InstallationService installationService;

    @Autowired
    private OwnedAppService ownedAppService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("我的组织页：OWNER 归属行渲染组织名、角色徽标与管理入口")
    void myOrgsPageRendersOwnerMembership() throws Exception {
        seedOrg("D0-研发一部");
        MvcResult result = this.mockMvc
                .perform(get("/selfservice/my-orgs")
                        .locale(Locale.SIMPLIFIED_CHINESE)
                        .with(user(SUPERADMIN_USERNAME).roles("SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(not(containsString("C0101"))))
                .andReturn();
        assertThat(bodyOf(result))
                .contains("我的组织")
                .contains("新建组织")
                .contains("D0-研发一部")
                .contains("OWNER")
                .contains("安装审批")
                .contains("组织应用");
    }

    @Test
    @DisplayName("org 应用页：OWNER 访问渲染 org 徽标、应用行与注册表单")
    void orgAppsPageRendersRegisteredOrgApp() throws Exception {
        Org org = seedOrg("D0-组织应用部");
        this.ownedAppService.registerOrg(org.id(), "D0-组织门户", Set.of(REDIRECT_URI), false);
        MvcResult result = this.mockMvc
                .perform(get("/selfservice/orgs/" + org.id() + "/apps")
                        .locale(Locale.SIMPLIFIED_CHINESE)
                        .with(user(SUPERADMIN_USERNAME).roles("SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(not(containsString("C0101"))))
                .andReturn();
        assertThat(bodyOf(result))
                .contains("D0-组织应用部")
                .contains("D0-组织门户")
                .contains("app_")
                .contains("注册组织应用");
    }

    @Test
    @DisplayName("安装审批页：PENDING 待批区渲染发起人、请求范围与发起表单")
    void installationsPageRendersPendingRequest() throws Exception {
        Org org = seedOrg("D0-安装审批部");
        OwnedAppService.Registration registration =
                this.ownedAppService.registerOrg(org.id(), "D0-审批演示应用", Set.of(REDIRECT_URI), false);
        this.installationService.request(
                registration.app().id(),
                org.id(),
                Set.of("openid", "profile"),
                ensureRequester().id());
        MvcResult result = this.mockMvc
                .perform(get("/selfservice/orgs/" + org.id() + "/installations")
                        .locale(Locale.SIMPLIFIED_CHINESE)
                        .with(user(SUPERADMIN_USERNAME).roles("SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(not(containsString("C0101"))))
                .andReturn();
        assertThat(bodyOf(result))
                .contains("安装审批")
                .contains("D0-安装审批部")
                .contains("待批请求")
                .contains("D0-审批演示应用")
                .contains(REQUESTER_USERNAME)
                .contains("发起安装");
    }

    @Test
    @DisplayName("负路径：不存在的 orgId 先撞 OWNER 门——200 JSON 语义码 A0508（家族惯例，非 403/404）")
    void nonexistentOrgFailsOwnerGateWithA0508() throws Exception {
        this.mockMvc
                .perform(get("/selfservice/orgs/d0-nonexistent-org/apps")
                        .locale(Locale.SIMPLIFIED_CHINESE)
                        .with(user(SUPERADMIN_USERNAME).roles("SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("A0508"));
    }

    /** 建一个超管名下的 org（创建者自动 OWNER），方法内一次性消费。 */
    private Org seedOrg(String name) {
        return this.orgService.create(name, superadminId());
    }

    private String superadminId() {
        return this.userRepository.findByUsername(SUPERADMIN_USERNAME).id();
    }

    /** 幂等建号（域面直建，照 selfservice E2E 的 ensureUser 惯例）。 */
    private JauthUser ensureRequester() {
        JauthUser existing = this.userRepository.findByUsername(REQUESTER_USERNAME);
        if (existing != null) {
            return existing;
        }
        JauthUser user = new JauthUser(
                UuidV7.generate().toString(),
                REQUESTER_USERNAME,
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
