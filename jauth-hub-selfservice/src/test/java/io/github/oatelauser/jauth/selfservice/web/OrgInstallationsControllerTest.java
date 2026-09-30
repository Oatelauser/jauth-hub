package io.github.oatelauser.jauth.selfservice.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.oatelauser.jauth.core.audit.AuditEvent;
import io.github.oatelauser.jauth.core.org.InMemoryInstallationRepository;
import io.github.oatelauser.jauth.core.org.InMemoryOrgRepository;
import io.github.oatelauser.jauth.core.org.Installation;
import io.github.oatelauser.jauth.core.org.InstallationService;
import io.github.oatelauser.jauth.core.org.Org;
import io.github.oatelauser.jauth.core.org.OrgMember;
import io.github.oatelauser.jauth.core.org.OrgRepository;
import io.github.oatelauser.jauth.core.org.OrgRole;
import io.github.oatelauser.jauth.core.org.OrgService;
import io.github.oatelauser.jauth.core.response.DefaultResponseRenderer;
import io.github.oatelauser.jauth.core.response.JauthResponseAdvice;
import io.github.oatelauser.jauth.core.scope.InMemoryScopeCatalog;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.i18n.FixedLocaleResolver;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * 安装审批页（B11）：DOM 三态断言（PENDING 待批勾选默认全选/全量表状态徽标与撤销钮/发起表单，照
 * MyAppsControllerTest 手装 Thymeleaf 模式）+ JSON 动作面（approve 收窄/空勾选 A0511、request 任何人可发、
 * reject/revoke、越权 A0508）。受测域件全用 core 内存真路径（InMemory* 仓储 + 两服务）。
 *
 * @author oatelauser
 */
class OrgInstallationsControllerTest {

    private static final String SELF_SERVICE_TEMPLATES = "io/github/oatelauser/jauth/selfservice/web/templates/";

    private static final String CORE_TEMPLATES = "io/github/oatelauser/jauth/core/web/templates/";

    private static final Instant T0 = Instant.parse("2026-09-30T10:00:00Z");

    private static final String ALICE = "alice";

    private static final String BOB = "bob";

    /** 局外人：在用户池但与 org 无归属关系（越权 A0508 与重复发起 A0506 的发起面用）。 */
    private static final String CAROL = "carol";

    private UserRepository users;

    private OrgRepository orgRepository;

    private InMemoryInstallationRepository installationRepository;

    private InstallationService installationService;

    private RegisteredClientRepository clients;

    private Org org;

    private final List<AuditEvent> auditLog = new ArrayList<>();

    @BeforeEach
    void setUp() {
        JauthUser alice = user("user-alice", ALICE);
        JauthUser bob = user("user-bob", BOB);
        JauthUser carol = user("user-carol", CAROL);
        this.users = mock(UserRepository.class);
        when(this.users.findByUsername(ALICE)).thenReturn(alice);
        when(this.users.findByUsername(BOB)).thenReturn(bob);
        when(this.users.findByUsername(CAROL)).thenReturn(carol);
        when(this.users.findById("user-alice")).thenReturn(alice);
        when(this.users.findById("user-bob")).thenReturn(bob);

        this.clients = new InMemoryRegisteredClientRepository(RegisteredClient.withId("client-1")
                .clientId("app_orgportal")
                .clientName("组织门户")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://portal.example.com/cb")
                .scope("openid")
                .scope("profile")
                .build());

        this.orgRepository = new InMemoryOrgRepository();
        this.installationRepository = new InMemoryInstallationRepository();
        OrgService orgService =
                new OrgService(this.orgRepository, this.auditLog::add, Clock.fixed(T0, ZoneOffset.UTC), null);
        this.installationService = new InstallationService(
                this.installationRepository,
                this.orgRepository,
                orgService,
                this.clients,
                this.auditLog::add,
                Clock.fixed(T0, ZoneOffset.UTC));
        this.org = orgService.create("acme", "user-alice");
        this.orgRepository.saveMember(new OrgMember(this.org.id(), "user-bob", OrgRole.MEMBER, T0));
    }

    @Test
    @DisplayName("PENDING 态（OWNER 视角）：待批区含发起人名/请求范围勾选默认全选/批准与驳回钮，全量表含状态徽标；发起表单含目录勾选")
    void pendingPageRendersRequesterCheckboxesAndActions() throws Exception {
        this.installationService.request("client-1", this.org.id(), Set.of("openid", "profile"), "user-bob");
        Installation installation = this.installationRepository.findByClientAndOrg("client-1", this.org.id());

        pages().perform(get(installationsPath()).principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("安装审批")))
                .andExpect(content().string(containsString("acme")))
                .andExpect(content().string(containsString("组织门户")))
                .andExpect(content().string(containsString("app_orgportal")))
                .andExpect(content().string(containsString("bob")))
                .andExpect(content()
                        .string(containsString("type=\"checkbox\" name=\"ceilingScope\" value=\"openid\" checked")))
                .andExpect(content()
                        .string(containsString("type=\"checkbox\" name=\"ceilingScope\" value=\"profile\" checked")))
                .andExpect(content()
                        .string(containsString("data-approve-url=\"/selfservice/orgs/" + this.org.id()
                                + "/installations/" + installation.id() + "/approve\"")))
                .andExpect(content()
                        .string(containsString("data-reject-url=\"/selfservice/orgs/" + this.org.id()
                                + "/installations/" + installation.id() + "/reject\"")))
                .andExpect(content().string(containsString("name=\"clientId\"")))
                .andExpect(content().string(containsString("type=\"checkbox\" name=\"scope\" value=\"openid\"")))
                .andExpect(content().string(containsString("确认你的身份标识（openid）")))
                .andExpect(content().string(containsString("待批")))
                .andExpect(content().string(containsString("发生了什么")));
    }

    @Test
    @DisplayName("APPROVED 态：全量表含已生效徽标/ceiling/审批人名与撤销钮；PENDING 区清空")
    void approvedPageRendersCeilingAndRevokeButton() throws Exception {
        Installation installation =
                this.installationService.request("client-1", this.org.id(), Set.of("openid", "profile"), "user-bob");
        this.installationService.approve(installation.id(), "user-alice", Set.of("openid"));

        pages().perform(get(installationsPath()).principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("已生效")))
                .andExpect(content().string(containsString(">openid</td>")))
                .andExpect(content().string(containsString("alice")))
                .andExpect(content()
                        .string(containsString("data-revoke-url=\"/selfservice/orgs/" + this.org.id()
                                + "/installations/" + installation.id() + "/revoke\"")))
                // 负断言盯按钮属性形态：JS 选择器字符串里的 [data-approve-url] 不受此约束
                .andExpect(content().string(not(containsString("data-approve-url=\""))));
    }

    @Test
    @DisplayName("REJECTED/REVOKED 徽标与撤销钮语义：驳回后无撤销钮（仅 APPROVED 可撤）")
    void rejectedPageRendersBadgeWithoutRevoke() throws Exception {
        Installation installation =
                this.installationService.request("client-1", this.org.id(), Set.of("openid"), "user-bob");
        this.installationService.reject(installation.id(), "user-alice");

        pages().perform(get(installationsPath()).principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("已驳回")))
                .andExpect(content().string(not(containsString("data-revoke-url=\""))));
    }

    @Test
    @DisplayName("越权门：MEMBER/局外人 GET 页与 OWNER 动作均 A0508（页面入口 + 服务层双证）")
    void nonOwnerGetsA0508() throws Exception {
        Installation installation =
                this.installationService.request("client-1", this.org.id(), Set.of("openid"), "user-bob");
        api().perform(get(installationsPath()).principal(() -> BOB))
                .andExpect(jsonPath("$.code").value("A0508"));
        api().perform(get(installationsPath()).principal(() -> "carol"))
                .andExpect(jsonPath("$.code").value("A0508"));
        api().perform(post(approvePath(installation.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopes\":[\"openid\"]}")
                        .principal(() -> BOB))
                .andExpect(jsonPath("$.code").value("A0508"));
        api().perform(post(rejectPath(installation.id())).principal(() -> BOB))
                .andExpect(jsonPath("$.code").value("A0508"));
    }

    @Test
    @DisplayName("批准 JSON：勾选集收窄为 ceiling（⊆ requested 由服务层强制），空勾选 A0511")
    void approveNarrowsCeilingAndRejectsEmptySelection() throws Exception {
        Installation installation =
                this.installationService.request("client-1", this.org.id(), Set.of("openid", "profile"), "user-bob");

        api().perform(post(approvePath(installation.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopes\":[]}")
                        .principal(() -> ALICE))
                .andExpect(jsonPath("$.code").value("A0511"));

        api().perform(post(approvePath(installation.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopes\":[\"openid\"]}")
                        .principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.ceilingScopes[0]").value("openid"));
        Installation approved = this.installationRepository.findById(installation.id());
        assertThat(approved.ceilingScopes()).containsExactly("openid");
        assertThat(approved.approvedBy()).isEqualTo("user-alice");
    }

    @Test
    @DisplayName("发起 JSON：MEMBER 可发起（拍板：控制点在审批不在发起）；未知 client B0502、空 scope A0505、PENDING 重复 A0506")
    void requestValidatesAndAllowsAnyLoggedInUser() throws Exception {
        api().perform(post(installationsPath())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"app_orgportal\",\"scopes\":[\"openid\",\"profile\"]}")
                        .principal(() -> BOB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        Installation installation = this.installationRepository.findByClientAndOrg("client-1", this.org.id());
        assertThat(installation.requestedBy()).isEqualTo("user-bob");

        api().perform(post(installationsPath())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"app_orgportal\",\"scopes\":[]}")
                        .principal(() -> BOB))
                .andExpect(jsonPath("$.code").value("A0505"));
        api().perform(post(installationsPath())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"app_orgportal\",\"scopes\":[\"openid\",\"admin\"]}")
                        .principal(() -> BOB))
                .andExpect(jsonPath("$.code").value("A0505"));
        api().perform(post(installationsPath())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"no-such-app\",\"scopes\":[\"openid\"]}")
                        .principal(() -> BOB))
                .andExpect(jsonPath("$.code").value("B0502"));
        api().perform(post(installationsPath())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"app_orgportal\",\"scopes\":[\"openid\"]}")
                        .principal(() -> "carol"))
                .andExpect(jsonPath("$.code").value("A0506"));
    }

    @Test
    @DisplayName("驳回/撤销 JSON：状态机转移由服务层强制（非 PENDING 批 A0507、非 APPROVED 撤 A0507）")
    void rejectAndRevokeTransitionStates() throws Exception {
        Installation installation =
                this.installationService.request("client-1", this.org.id(), Set.of("openid"), "user-bob");

        api().perform(post(rejectPath(installation.id())).principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));
        assertThat(this.installationRepository
                        .findById(installation.id())
                        .status()
                        .name())
                .isEqualTo("REJECTED");

        // REJECTED 行不能再批（A0507）；重发后走批准 → 撤销
        api().perform(post(approvePath(installation.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopes\":[\"openid\"]}")
                        .principal(() -> ALICE))
                .andExpect(jsonPath("$.code").value("A0507"));
        Installation resent = this.installationService.request("client-1", this.org.id(), Set.of("openid"), "user-bob");
        api().perform(post(approvePath(resent.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopes\":[\"openid\"]}")
                        .principal(() -> ALICE))
                .andExpect(status().isOk());
        api().perform(post(revokePath(resent.id())).principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));
        assertThat(this.installationRepository.findById(resent.id()).status().name())
                .isEqualTo("REVOKED");
    }

    @Test
    @DisplayName("未认证 A0503；未知 org 的页面入口同走 A0508（OWNER 门先于 org 存在性）")
    void gatesUnauthenticatedAndUnknownOrg() throws Exception {
        api().perform(post(installationsPath())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"app_orgportal\",\"scopes\":[\"openid\"]}"))
                .andExpect(jsonPath("$.code").value("A0503"));
        api().perform(get("/selfservice/orgs/00000000-0000-7000-8000-0000000000ff/installations")
                        .principal(() -> ALICE))
                .andExpect(jsonPath("$.code").value("A0508"));
    }

    private String installationsPath() {
        return "/selfservice/orgs/" + this.org.id() + "/installations";
    }

    private String approvePath(String installationId) {
        return installationsPath() + "/" + installationId + "/approve";
    }

    private String rejectPath(String installationId) {
        return installationsPath() + "/" + installationId + "/reject";
    }

    private String revokePath(String installationId) {
        return installationsPath() + "/" + installationId + "/revoke";
    }

    /** DOM 面（手装 Thymeleaf 双解析器 + 双 basename + advice 越权 JSON 断言）。 */
    private MockMvc pages() {
        return MockMvcBuilders.standaloneSetup(newController())
                .setViewResolvers(viewResolver())
                .setLocaleResolver(new FixedLocaleResolver(Locale.SIMPLIFIED_CHINESE))
                .addFilters((request, response, chain) -> {
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    chain.doFilter(request, response);
                })
                .build();
    }

    /** JSON 面（advice 手挂：JauthException → SPI 失败体，生产由 starter 装配）。 */
    private MockMvc api() {
        return MockMvcBuilders.standaloneSetup(newController())
                .setControllerAdvice(jauthAdvice())
                .build();
    }

    private OrgInstallationsController newController() {
        return new OrgInstallationsController(
                new OrgService(this.orgRepository, this.auditLog::add, Clock.fixed(T0, ZoneOffset.UTC), null),
                this.installationService,
                this.installationRepository,
                this.orgRepository,
                this.users,
                this.clients,
                new InMemoryScopeCatalog(),
                messageSource(),
                EducationalFlag.ON,
                new DefaultResponseRenderer());
    }

    private static JauthResponseAdvice jauthAdvice() {
        return new JauthResponseAdvice(new DefaultResponseRenderer());
    }

    private ThymeleafViewResolver viewResolver() {
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(templateResolver(SELF_SERVICE_TEMPLATES));
        engine.addTemplateResolver(templateResolver(CORE_TEMPLATES));
        engine.setMessageSource(messageSource());
        ThymeleafViewResolver viewResolver = new ThymeleafViewResolver();
        viewResolver.setTemplateEngine(engine);
        viewResolver.setContentType("text/html;charset=UTF-8");
        viewResolver.setForceContentType(true);
        return viewResolver;
    }

    private ClassLoaderTemplateResolver templateResolver(String prefix) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix(prefix);
        resolver.setSuffix(".html");
        resolver.setCacheable(false);
        resolver.setCheckExistence(true);
        return resolver;
    }

    private MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasenames(
                "io/github/oatelauser/jauth/selfservice/i18n/messages",
                "io/github/oatelauser/jauth/core/i18n/messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        return source;
    }

    private static JauthUser user(String id, String username) {
        return new JauthUser(
                id,
                username,
                "placeholder-password-hash-not-real",
                null,
                null,
                JauthUser.ROLE_USER,
                JauthUser.STATUS_ACTIVE,
                null,
                T0);
    }
}
