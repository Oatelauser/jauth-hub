package io.github.oatelauser.jauth.selfservice.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.oatelauser.jauth.core.passkey.InMemoryPasskeyCredentialRepository;
import io.github.oatelauser.jauth.core.passkey.JauthUserEntityRepository;
import io.github.oatelauser.jauth.core.response.DefaultResponseRenderer;
import io.github.oatelauser.jauth.core.scope.InMemoryScopeCatalog;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import io.github.oatelauser.jauth.core.web.PasskeyFlag;
import io.github.oatelauser.jauth.selfservice.pat.InMemoryPatService;
import io.github.oatelauser.jauth.selfservice.pat.PatRecord;
import io.github.oatelauser.jauth.selfservice.pat.PatService;
import io.github.oatelauser.jauth.selfservice.pat.PatStatus;
import io.github.oatelauser.jauth.selfservice.support.Providers;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.i18n.FixedLocaleResolver;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * 自助两页 DOM 级断言（照 core ProtocolPagesTest 的手装 Thymeleaf 模式）：教学开关显隐、PAT 创建表单（scope
 * 勾选/有效期阶梯默认 90）、过期徽标、memory 门控提示态、看板行与解除授权按钮。
 *
 * <p>过期徽标技巧：服务时钟钉 T0（创建即过期时间锚点），控制器时钟钉 T0+40d——30 天有效期的令牌在视图里已过期。
 *
 * @author oatelauser
 */
class SelfServicePagesTest {

    private static final String SELF_SERVICE_TEMPLATES = "io/github/oatelauser/jauth/selfservice/web/templates/";

    private static final String CORE_TEMPLATES = "io/github/oatelauser/jauth/core/web/templates/";

    private static final Instant T0 = Instant.parse("2026-09-29T10:00:00Z");

    private static final String ALICE = "alice";

    private UserRepository users;

    private PatService patService;

    @BeforeEach
    void setUp() {
        this.users = mock(UserRepository.class);
        when(this.users.findByUsername(ALICE)).thenReturn(alice());
        this.patService = new InMemoryPatService(Clock.fixed(T0, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("PAT 页：表单含名称必填输入/scope 勾选/有效期三档默认 90/列表含名称与前缀及过期徽标/zh 文案")
    void patPageRendersFormScopesValidityAndExpiredBadge() throws Exception {
        this.patService.create("user-alice", "CI 部署脚本", Set.of("openid"), Duration.ofDays(30));

        patPage(EducationalFlag.ON, this.patService, Clock.fixed(T0.plus(Duration.ofDays(40)), ZoneOffset.UTC))
                .perform(get("/selfservice/pat").principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("个人访问令牌")))
                .andExpect(content().string(containsString("action=\"/selfservice/pat\"")))
                .andExpect(content().string(containsString("name=\"name\"")))
                .andExpect(content().string(containsString("required=\"required\"")))
                .andExpect(content().string(containsString("type=\"checkbox\" name=\"scope\"" + " value=\"openid\"")))
                .andExpect(content().string(containsString("确认你的身份标识（openid）")))
                .andExpect(content().string(containsString("name=\"validityDays\"")))
                .andExpect(content().string(containsString("selected=\"selected\">90 天")))
                .andExpect(content().string(containsString("CI 部署脚本")))
                .andExpect(content().string(containsString("jpat_")))
                .andExpect(content().string(containsString("已过期")))
                .andExpect(content().string(containsString("发生了什么")))
                .andExpect(content().string(containsString("明文只显示这一次")));
    }

    @Test
    @DisplayName("PAT 页存量行空名回退：V7 前的行（name=NULL）展示 i18n 未命名")
    void patPageFallsBackToUnnamedForLegacyNullNameRows() throws Exception {
        PatService legacy = mock(PatService.class);
        PatRecord unnamed = new PatRecord(
                "pat-legacy",
                "user-alice",
                null,
                "jpat_legacy00",
                Set.of("openid"),
                PatStatus.ACTIVE,
                T0,
                T0.plus(Duration.ofDays(90)),
                null);
        when(legacy.listActive("user-alice")).thenReturn(List.of(unnamed));

        patPage(EducationalFlag.ON, legacy, Clock.fixed(T0, ZoneOffset.UTC))
                .perform(get("/selfservice/pat").principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("未命名")))
                .andExpect(content().string(containsString("jpat_legacy00")));
    }

    @Test
    @DisplayName("PAT 页 memory 门控：渲染不支持提示（200，不 500），表单与列表不渲染")
    void patPageInMemoryModeRendersNoticeInsteadOfForm() throws Exception {
        patPage(EducationalFlag.ON, null, Clock.fixed(T0, ZoneOffset.UTC))
                .perform(get("/selfservice/pat").principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("当前存储模式（memory）不支持个人访问令牌")))
                .andExpect(content().string(not(containsString("name=\"scope\""))))
                .andExpect(content().string(not(containsString("validityDays"))));
    }

    @Test
    @DisplayName("教学开关关闭：两页教学块不渲染")
    void educationalFalseHidesTeachingBlocks() throws Exception {
        patPage(() -> false, this.patService, Clock.fixed(T0, ZoneOffset.UTC))
                .perform(get("/selfservice/pat").principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("发生了什么"))));
        appsPage(() -> false, mockAppsService())
                .perform(get("/selfservice/apps").principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("发生了什么"))));
    }

    @Test
    @DisplayName("看板页：client 展示名/scope/授权时间/解除授权按钮")
    void appsPageRendersAuthorizedRows() throws Exception {
        appsPage(EducationalFlag.ON, mockAppsService())
                .perform(get("/selfservice/apps").principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("已授权应用")))
                .andExpect(content().string(containsString("演示看板应用")))
                .andExpect(content().string(containsString("openid profile")))
                // #temporals.format 走服务器默认时区，期望值同规则生成（时区无关断言）
                .andExpect(content().string(containsString(expectedLocalTime())))
                .andExpect(content().string(containsString("解除授权")))
                .andExpect(content().string(containsString("data-revoke-url=\"/selfservice/apps/client-a/revoke\"")))
                .andExpect(content().string(containsString("发生了什么")));
    }

    @Test
    @DisplayName("看板页 memory 门控：渲染不支持提示，列表不渲染")
    void appsPageInMemoryModeRendersNotice() throws Exception {
        appsPage(EducationalFlag.ON, null)
                .perform(get("/selfservice/apps").principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("当前存储模式（memory）不支持授权看板")))
                // 教学文案会提到"解除授权"字样，负断言盯按钮的 data-revoke-url
                .andExpect(content().string(not(containsString("data-revoke-url"))));
    }

    @Test
    @DisplayName("通行密钥页：注册表单（label 必填）/凭据列表（label、ID 缩略、删除端点接线、时间列）/教学块/zh 文案")
    void passkeyPageRendersFormListAndDeleteWiring() throws Exception {
        byte[] credentialId = new byte[16];
        for (int i = 0; i < credentialId.length; i++) {
            credentialId[i] = (byte) i;
        }
        String expectedId = new Bytes(credentialId).toBase64UrlString();
        InMemoryPasskeyCredentialRepository credentials = new InMemoryPasskeyCredentialRepository(event -> {});
        credentials.save(credentialRecord("我的手机", credentialId));

        passkeyPage(EducationalFlag.ON, credentials)
                .perform(get("/selfservice/passkey").principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("通行密钥")))
                .andExpect(content().string(containsString("name=\"label\"")))
                .andExpect(content().string(containsString("required=\"required\"")))
                .andExpect(content().string(containsString("添加通行密钥")))
                .andExpect(content().string(containsString("我的手机")))
                .andExpect(content().string(containsString(expectedId.substring(0, 12) + "…")))
                // 删除走框架 DELETE 端点：credentialId 原样作路径段（base64url URL 安全）
                .andExpect(
                        content().string(containsString("data-delete-url=\"/webauthn/register/" + expectedId + "\"")))
                .andExpect(content().string(containsString(expectedLocalTime())))
                .andExpect(content().string(containsString("发生了什么")))
                .andExpect(content().string(containsString("私钥")))
                // Thymeleaf javascript 内联把 @{...} 的 '/' 转义为 '\/'（运行时等价），断言按转义后字面量
                .andExpect(content().string(containsString("\\/webauthn\\/register\\/options")));
    }

    @Test
    @DisplayName("通行密钥页未启用（凭据仓储缺席 = jauth-hub.passkey.enabled=false）：渲染未启用提示，表单/列表/脚本不渲染")
    void passkeyPageDisabledRendersNoticeInsteadOfForm() throws Exception {
        passkeyPage(EducationalFlag.ON, null)
                .perform(get("/selfservice/passkey").principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("通行密钥未启用")))
                .andExpect(content().string(not(containsString("name=\"label\""))))
                .andExpect(content().string(not(containsString("/webauthn/register"))));
    }

    @Test
    @DisplayName("看板导航：passkey 开启渲染通行密钥入口，默认关零可见（B12：locale 已钉 zh，断言中文词条）")
    void appsPageNavEntryFollowsPasskeyFlag() throws Exception {
        appsPage(EducationalFlag.ON, mockAppsService(), () -> true)
                .perform(get("/selfservice/apps").principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/selfservice/passkey\"")))
                .andExpect(content().string(containsString("通行密钥")));
        appsPage(EducationalFlag.ON, mockAppsService(), PasskeyFlag.OFF)
                .perform(get("/selfservice/apps").principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/selfservice/passkey"))));
    }

    private MockMvc passkeyPage(EducationalFlag educational, @Nullable UserCredentialRepository credentials) {
        PasskeyController controller = new PasskeyController(Providers.fixed(credentials), this.users, educational);
        return buildMockMvc(null, null, controller);
    }

    /** 合成凭据行（user handle = alice 的 jauth 用户 id，走 JauthUserEntityRepository 编解码单点）。 */
    private static CredentialRecord credentialRecord(String label, byte[] credentialId) {
        return ImmutableCredentialRecord.builder()
                .credentialId(new Bytes(credentialId))
                .userEntityUserId(JauthUserEntityRepository.userHandle("user-alice"))
                .publicKey(new ImmutablePublicKeyCose(new byte[] {0x01, 0x02, 0x03}))
                .signatureCount(0)
                .uvInitialized(true)
                .backupEligible(false)
                .backupState(false)
                .transports(Set.of())
                .created(T0)
                .lastUsed(T0)
                .label(label)
                .build();
    }

    private MockMvc patPage(EducationalFlag educational, PatService service, Clock viewClock) {
        PatController controller = new PatController(
                service,
                new InMemoryScopeCatalog(),
                this.users,
                messageSource(),
                educational,
                new DefaultResponseRenderer(),
                viewClock);
        return buildMockMvc(controller, null, null);
    }

    private MockMvc appsPage(EducationalFlag educational, AuthorizedAppService appService) {
        return appsPage(educational, appService, PasskeyFlag.OFF);
    }

    private MockMvc appsPage(EducationalFlag educational, AuthorizedAppService appService, PasskeyFlag passkey) {
        AuthorizedAppsController controller = new AuthorizedAppsController(
                appService,
                Providers.fixed(mock(
                        org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService.class)),
                Providers.fixed(mock(
                        org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService
                                .class)),
                mockClients(),
                educational,
                passkey,
                new DefaultResponseRenderer());
        return buildMockMvc(null, controller, null);
    }

    /** 手装 Thymeleaf：双解析器（selfservice 命名空间优先，core 兜底供 fragments/layout），双 basename 消息源。 */
    private MockMvc buildMockMvc(
            PatController patController, AuthorizedAppsController appsController, PasskeyController passkeyController) {
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(templateResolver(SELF_SERVICE_TEMPLATES));
        engine.addTemplateResolver(templateResolver(CORE_TEMPLATES));
        engine.setMessageSource(messageSource());

        ThymeleafViewResolver viewResolver = new ThymeleafViewResolver();
        viewResolver.setTemplateEngine(engine);
        viewResolver.setContentType("text/html;charset=UTF-8");
        viewResolver.setForceContentType(true);

        List<Object> controllers = new ArrayList<>();
        if (patController != null) {
            controllers.add(patController);
        }
        if (appsController != null) {
            controllers.add(appsController);
        }
        if (passkeyController != null) {
            controllers.add(passkeyController);
        }
        return MockMvcBuilders.standaloneSetup(controllers.toArray())
                .setViewResolvers(viewResolver)
                .setLocaleResolver(new FixedLocaleResolver(Locale.SIMPLIFIED_CHINESE))
                .addFilters((request, response, chain) -> {
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    chain.doFilter(request, response);
                })
                .build();
    }

    /** T0 在服务器默认时区的"yyyy-MM-dd HH:mm"呈现（与模板 #temporals.format 同规则）。 */
    private static String expectedLocalTime() {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(java.time.ZoneId.systemDefault())
                .format(T0);
    }

    private ClassLoaderTemplateResolver templateResolver(String prefix) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix(prefix);
        resolver.setSuffix(".html");
        resolver.setCacheable(false);
        // 双解析器链的先决条件：未命中必须返回 null 让链继续（fragments/layout 在 core 命名空间）
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

    private AuthorizedAppService mockAppsService() {
        AuthorizedAppService service = mock(AuthorizedAppService.class);
        when(service.list(ALICE))
                .thenReturn(List.of(new AuthorizedApp(
                        "client-a", Set.of("openid", "profile"), Instant.parse("2026-09-29T10:00:00Z"))));
        return service;
    }

    private RegisteredClientRepository mockClients() {
        RegisteredClient client = RegisteredClient.withId("client-a")
                .clientId("dashboard-client-a")
                .clientName("演示看板应用")
                .clientAuthenticationMethod(org.springframework.security.oauth2.core.ClientAuthenticationMethod.NONE)
                .authorizationGrantType(
                        org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://placeholder.example.com/callback")
                .scope("openid")
                .build();
        RegisteredClientRepository repository = mock(RegisteredClientRepository.class);
        when(repository.findById("client-a")).thenReturn(client);
        return repository;
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
