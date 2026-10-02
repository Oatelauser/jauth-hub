package io.github.oatelauser.jauth.selfservice.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.oatelauser.jauth.core.client.InMemoryClientOwnerResolver;
import io.github.oatelauser.jauth.core.response.DefaultResponseRenderer;
import io.github.oatelauser.jauth.core.response.JauthResponseAdvice;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.i18n.FixedLocaleResolver;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * 我的应用页（B10）：列表/注册表单 DOM 断言（照 SelfServicePagesTest 手装 Thymeleaf 模式）+ 注册 JSON 面
 * （机密 secret 仅此一次、公开无 secret、名称/redirect 校验 A0509/A0510、未认证 A0503）。memory 实现做受测
 * 服务——本页两存储模式同契约。
 *
 * @author oatelauser
 */
class MyAppsControllerTest {

    private static final String SELF_SERVICE_TEMPLATES = "io/github/oatelauser/jauth/selfservice/web/templates/";

    private static final String CORE_TEMPLATES = "io/github/oatelauser/jauth/core/web/templates/";

    private static final Instant T0 = Instant.parse("2026-09-30T10:00:00Z");

    private static final String ALICE = "alice";

    private UserRepository users;

    private InMemoryOwnedAppService ownedAppService;

    @BeforeEach
    void setUp() {
        this.users = mock(UserRepository.class);
        when(this.users.findByUsername(ALICE)).thenReturn(alice());
        this.ownedAppService = new InMemoryOwnedAppService(
                new InMemoryRegisteredClientRepository(RegisteredClient.withId("seed-platform-1")
                        .clientId("seed-platform")
                        .clientName("platform seed")
                        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                        .build()),
                new InMemoryClientOwnerResolver(),
                new io.github.oatelauser.jauth.core.token.InMemoryTokenFamilyService(),
                new BCryptPasswordEncoder(),
                new io.github.oatelauser.jauth.core.scope.InMemoryScopeCatalog(),
                java.time.Clock.fixed(T0, java.time.ZoneOffset.UTC));
    }

    @Test
    @DisplayName("列表页：注册入口 + 应用行（名称/Client ID/类型徽标/回调/zh 文案）")
    void listPageRendersOwnedAppRows() throws Exception {
        this.ownedAppService.register("user-alice", "CI 看板", java.util.Set.of("https://ci.example.com/cb"), true);
        this.ownedAppService.register("user-alice", "移动端", java.util.Set.of("https://m.example.com/cb"), false);

        pages().perform(get("/selfservice/my-apps").principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(contentContains("我的应用")))
                .andExpect(content().string(contentContains("href=\"/selfservice/my-apps/new\"")))
                .andExpect(content().string(contentContains("CI 看板")))
                .andExpect(content().string(contentContains("移动端")))
                .andExpect(content().string(contentContains("app_")))
                .andExpect(content().string(contentContains("机密")))
                .andExpect(content().string(contentContains("公开")))
                .andExpect(content().string(contentContains("https://ci.example.com/cb")))
                .andExpect(content().string(contentContains("发生了什么")));
    }

    @Test
    @DisplayName("注册表单页：最小三件（名称必填/redirect 多行/类型选择）+ JS 端点指向 /selfservice/my-apps")
    void newAppPageRendersMinimalForm() throws Exception {
        pages().perform(get("/selfservice/my-apps/new").principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(content().string(contentContains("注册新应用")))
                .andExpect(content().string(contentContains("name=\"name\"")))
                .andExpect(content().string(contentContains("required=\"required\"")))
                .andExpect(content().string(contentContains("textarea name=\"redirectUris\"")))
                .andExpect(content().string(contentContains("name=\"confidential\"")))
                .andExpect(content().string(contentContains("\"/selfservice/my-apps\"")));
    }

    @Test
    @DisplayName("服务缺席门控：两页渲染不支持提示（200，不 500），表单不渲染")
    void pagesRenderNoticeWhenServiceMissing() throws Exception {
        MockMvc missing = MockMvcBuilders.standaloneSetup(new MyAppsController(
                        null, this.users, EducationalFlag.ON, new DefaultResponseRenderer(), event -> {}))
                .setViewResolvers(viewResolver())
                .setLocaleResolver(new FixedLocaleResolver(Locale.SIMPLIFIED_CHINESE))
                .addFilters((request, response, chain) -> {
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    chain.doFilter(request, response);
                })
                .build();
        missing.perform(get("/selfservice/my-apps").principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(content().string(contentContains("不支持应用自助注册")))
                .andExpect(content()
                        .string(org.hamcrest.Matchers.not(contentContains("href=\"/selfservice/my-apps/new\""))));
    }

    @Test
    @DisplayName("注册 JSON（机密）：clientId 带 app_ 头、secret 仅此一次、列表回查无明文")
    void registerConfidentialReturnsSecretOnce() throws Exception {
        String body = api().perform(post("/selfservice/my-apps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"CI 看板\",\"redirectUris\":\"https://ci.example.com/cb\","
                                + "\"confidential\":true}")
                        .principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.clientId", startsWith("app_")))
                .andExpect(jsonPath("$.data.clientSecret").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("\"confidential\":true");

        // 列表行/存储侧永不出现明文 secret（服务契约 + 页面投影双证明）
        assertThat(this.ownedAppService.list("user-alice").get(0).toString()).doesNotContain(extractSecret(body));
    }

    @Test
    @DisplayName("注册 JSON（公开）：无 clientSecret 字段，A0504 之外不设障碍")
    void registerPublicHasNoSecret() throws Exception {
        api().perform(post("/selfservice/my-apps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"移动端\",\"redirectUris\":\"http://localhost:8080/callback\"}")
                        .principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.clientSecret").doesNotExist())
                .andExpect(jsonPath("$.data.confidential").value(false));
    }

    @Test
    @DisplayName("注册校验：名空/超长 A0509；redirect 空/非法/带 fragment A0510")
    void registerValidatesNameAndRedirects() throws Exception {
        api().perform(post("/selfservice/my-apps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \",\"redirectUris\":\"https://a.example.com/cb\"}")
                        .principal(() -> ALICE))
                .andExpect(jsonPath("$.code").value("A0509"));
        api().perform(post("/selfservice/my-apps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"redirectUris\":\"\"}")
                        .principal(() -> ALICE))
                .andExpect(jsonPath("$.code").value("A0510"));
        api().perform(post("/selfservice/my-apps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"redirectUris\":\"not a url\"}")
                        .principal(() -> ALICE))
                .andExpect(jsonPath("$.code").value("A0510"));
        api().perform(post("/selfservice/my-apps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"redirectUris\":\"https://a.example.com/cb#frag\"}")
                        .principal(() -> ALICE))
                .andExpect(jsonPath("$.code").value("A0510"));
    }

    @Test
    @DisplayName("未认证回 A0503；服务缺席回 A0504")
    void registerRejectsMissingPrincipalAndService() throws Exception {
        api().perform(post("/selfservice/my-apps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"redirectUris\":\"https://a.example.com/cb\"}"))
                .andExpect(jsonPath("$.code").value("A0503"));
        MockMvc missing = MockMvcBuilders.standaloneSetup(new MyAppsController(
                        null, this.users, EducationalFlag.ON, new DefaultResponseRenderer(), event -> {}))
                .setControllerAdvice(jauthAdvice())
                .build();
        missing.perform(post("/selfservice/my-apps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"redirectUris\":\"https://a.example.com/cb\"}")
                        .principal(() -> ALICE))
                .andExpect(jsonPath("$.code").value("A0504"));
    }

    /** DOM 面（手装 Thymeleaf 双解析器 + 双 basename，同 SelfServicePagesTest）。 */
    private MockMvc pages() {
        return MockMvcBuilders.standaloneSetup(new MyAppsController(
                        this.ownedAppService,
                        this.users,
                        EducationalFlag.ON,
                        new DefaultResponseRenderer(),
                        event -> {}))
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
        return MockMvcBuilders.standaloneSetup(new MyAppsController(
                        this.ownedAppService,
                        this.users,
                        EducationalFlag.ON,
                        new DefaultResponseRenderer(),
                        event -> {}))
                .setControllerAdvice(jauthAdvice())
                .build();
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

    /** 从注册响应提取明文 secret（测试辅助；响应即明文的唯一出现点）。 */
    private static String extractSecret(String responseBody) {
        int index = responseBody.indexOf("\"clientSecret\":\"") + "\"clientSecret\":\"".length();
        return responseBody.substring(index, responseBody.indexOf('"', index));
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

    /** hamcrest containsString 直引（中文断言可读性）。 */
    private static org.hamcrest.Matcher<String> contentContains(String expected) {
        return org.hamcrest.Matchers.containsString(expected);
    }
}
