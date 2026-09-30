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
import io.github.oatelauser.jauth.core.org.InMemoryOrgRepository;
import io.github.oatelauser.jauth.core.org.Org;
import io.github.oatelauser.jauth.core.org.OrgMember;
import io.github.oatelauser.jauth.core.org.OrgRole;
import io.github.oatelauser.jauth.core.org.OrgService;
import io.github.oatelauser.jauth.core.response.DefaultResponseRenderer;
import io.github.oatelauser.jauth.core.response.JauthResponseAdvice;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.i18n.FixedLocaleResolver;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * 我的组织页（B11）：列表/新建表单 DOM 断言（照 MyAppsControllerTest 手装 Thymeleaf 模式）+ 创建 JSON 面
 * （成功建号自动 OWNER、重名 A0506、名空/超长 A0501/A0502、未认证 A0503、服务缺席 A0504）。受测服务用 core
 * 内存件（InMemoryOrgRepository + OrgService 真路径）——页面两存储模式同契约。
 *
 * @author oatelauser
 */
class MyOrgsControllerTest {

    private static final String SELF_SERVICE_TEMPLATES = "io/github/oatelauser/jauth/selfservice/web/templates/";

    private static final String CORE_TEMPLATES = "io/github/oatelauser/jauth/core/web/templates/";

    private static final Instant T0 = Instant.parse("2026-09-30T10:00:00Z");

    private static final String ALICE = "alice";

    private UserRepository users;

    private OrgService orgService;

    private InMemoryOrgRepository orgRepository;

    private final List<AuditEvent> auditLog = new ArrayList<>();

    @BeforeEach
    void setUp() {
        this.users = mock(UserRepository.class);
        when(this.users.findByUsername(ALICE)).thenReturn(alice());
        this.orgRepository = new InMemoryOrgRepository();
        this.orgService = new OrgService(this.orgRepository, this.auditLog::add, Clock.fixed(T0, ZoneOffset.UTC), null);
    }

    @Test
    @DisplayName("列表页：归属行（org 名/角色徽标）+ OWNER 行带审批与 org 应用入口 + 新建表单 + zh 文案")
    void listPageRendersMembershipsAndOwnerLinks() throws Exception {
        Org aliceOwns = this.orgService.create("acme", "user-alice");
        // MEMBER 行来自他人创建的 org：org 先落、成员关系经 saveMember 直落（无成员管理面，B11 明确不做）
        this.orgRepository.save(new Org("other-org-1", "globex", T0));
        this.orgRepository.saveMember(new OrgMember("other-org-1", "user-alice", OrgRole.MEMBER, T0));

        pages().perform(get("/selfservice/my-orgs").principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("我的组织")))
                .andExpect(content().string(containsString("acme")))
                .andExpect(content().string(containsString("OWNER")))
                .andExpect(content().string(containsString("MEMBER")))
                .andExpect(content()
                        .string(containsString("href=\"/selfservice/orgs/" + aliceOwns.id() + "/installations\"")))
                .andExpect(content().string(containsString("href=\"/selfservice/orgs/" + aliceOwns.id() + "/apps\"")))
                .andExpect(content().string(containsString("name=\"name\"")))
                .andExpect(content().string(containsString("maxlength=\"50\"")))
                .andExpect(content().string(containsString("org-create-form")))
                .andExpect(content().string(containsString("发生了什么")));
    }

    @Test
    @DisplayName("MEMBER 行不渲染管理入口（入口控制；越权访问由服务层 A0508 兜底）")
    void memberRowsHideManagementLinks() throws Exception {
        this.orgRepository.save(new Org("member-org-1", "globex", T0));
        this.orgRepository.saveMember(new OrgMember("member-org-1", "user-alice", OrgRole.MEMBER, T0));

        pages().perform(get("/selfservice/my-orgs").principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("globex")))
                .andExpect(content().string(not(containsString("/installations\""))))
                .andExpect(content().string(not(containsString("/apps\""))));
    }

    @Test
    @DisplayName("服务缺席门控：页面渲染不支持提示（200，不 500），表单不渲染")
    void pageRendersNoticeWhenServiceMissing() throws Exception {
        MockMvc missing = MockMvcBuilders.standaloneSetup(
                        new MyOrgsController(null, this.users, EducationalFlag.ON, new DefaultResponseRenderer()))
                .setViewResolvers(viewResolver())
                .setLocaleResolver(new FixedLocaleResolver(Locale.SIMPLIFIED_CHINESE))
                .addFilters((request, response, chain) -> {
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    chain.doFilter(request, response);
                })
                .build();
        missing.perform(get("/selfservice/my-orgs").principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("不支持组织自助管理")))
                .andExpect(content().string(not(containsString("name=\"name\""))));
    }

    @Test
    @DisplayName("创建 JSON：成功（创建者自动 OWNER，orgId 回填）；重名 A0506")
    void createMakesCreatorOwnerAndRejectsDuplicateName() throws Exception {
        api().perform(post("/selfservice/my-orgs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"acme\"}")
                        .principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.name").value("acme"));
        assertThat(this.orgService.isOwner(orgIdByName("acme"), "user-alice")).isTrue();

        api().perform(post("/selfservice/my-orgs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"acme\"}")
                        .principal(() -> ALICE))
                .andExpect(jsonPath("$.code").value("A0506"));
    }

    @Test
    @DisplayName("创建校验：名空 A0501、超 50 字符 A0502；未认证 A0503；服务缺席 A0504")
    void createValidatesNameAndGates() throws Exception {
        api().perform(post("/selfservice/my-orgs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \"}")
                        .principal(() -> ALICE))
                .andExpect(jsonPath("$.code").value("A0501"));
        api().perform(post("/selfservice/my-orgs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + "x".repeat(51) + "\"}")
                        .principal(() -> ALICE))
                .andExpect(jsonPath("$.code").value("A0502"));
        api().perform(post("/selfservice/my-orgs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"acme\"}"))
                .andExpect(jsonPath("$.code").value("A0503"));
        MockMvc missing = MockMvcBuilders.standaloneSetup(
                        new MyOrgsController(null, this.users, EducationalFlag.ON, new DefaultResponseRenderer()))
                .setControllerAdvice(jauthAdvice())
                .build();
        missing.perform(post("/selfservice/my-orgs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"acme\"}")
                        .principal(() -> ALICE))
                .andExpect(jsonPath("$.code").value("A0504"));
    }

    /** DOM 面（手装 Thymeleaf 双解析器 + 双 basename，同 MyAppsControllerTest）。 */
    private MockMvc pages() {
        return MockMvcBuilders.standaloneSetup(new MyOrgsController(
                        this.orgService, this.users, EducationalFlag.ON, new DefaultResponseRenderer()))
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
        return MockMvcBuilders.standaloneSetup(new MyOrgsController(
                        this.orgService, this.users, EducationalFlag.ON, new DefaultResponseRenderer()))
                .setControllerAdvice(jauthAdvice())
                .build();
    }

    private static JauthResponseAdvice jauthAdvice() {
        return new JauthResponseAdvice(new DefaultResponseRenderer());
    }

    private String orgIdByName(String name) {
        return this.orgRepository.findByName(name).id();
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

    private JauthUser alice() {
        return new JauthUser(
                "user-alice",
                ALICE,
                "placeholder-password-hash-not-real",
                null,
                null,
                JauthUser.ROLE_USER,
                JauthUser.STATUS_ACTIVE,
                null,
                T0);
    }
}
