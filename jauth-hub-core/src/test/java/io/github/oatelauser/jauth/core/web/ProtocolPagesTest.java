package io.github.oatelauser.jauth.core.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.oatelauser.jauth.core.scope.InMemoryScopeCatalog;
import jakarta.servlet.Filter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.i18n.FixedLocaleResolver;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * 协议三页 DOM 级断言：登录表单/CSRF、consent 勾选与 client 名、设备输码框、教学开关显隐、zh 文案。
 *
 * <p>standalone MockMvc + 手装 Thymeleaf（core 无自动配置，装配是 B4 的事）；CsrfFilter 单挂进链路以验证 模板 CSRF
 * 占位在真实过滤链下渲染出隐藏域。
 *
 * @author oatelauser
 */
class ProtocolPagesTest {

    private static final String TEMPLATE_PREFIX = "io/github/oatelauser/jauth/core/web/templates/";

    private static final String I18N_BASENAME = "io/github/oatelauser/jauth/core/i18n/messages";

    private static final String CLIENT_ID = "demo-client";

    private MockMvc educated;

    private MockMvc plain;

    /** 响应编码钉子：先于视图显式 setCharacterEncoding，ThymeleafView 后续 setLocale(zh) 不会覆盖已显式设置的编码。 */
    private static final Filter UTF8_RESPONSE_FILTER = (request, response, chain) -> {
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        chain.doFilter(request, response);
    };

    @BeforeEach
    void setUp() {
        MessageSource messageSource = messageSource();
        educated = buildMockMvc(EducationalFlag.ON, messageSource);
        plain = buildMockMvc(() -> false, messageSource);
    }

    @Test
    @DisplayName("登录页：表单/CSRF 占位/教学块/流程图/zh 文案齐备")
    void loginRendersFormCsrfAndTeachingInChinese() throws Exception {
        educated.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("action=\"/login\"")))
                .andExpect(content().string(containsString("method=\"post\"")))
                .andExpect(content().string(containsString("name=\"username\"")))
                .andExpect(content().string(containsString("name=\"password\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(containsString("登 录")))
                .andExpect(content().string(containsString("发生了什么")))
                .andExpect(content().string(containsString("class=\"steps\"")))
                .andExpect(content().string(containsString("应用发起授权，跳转登录")))
                .andExpect(content().string(containsString("/css/jauth.css")));
    }

    @Test
    @DisplayName("登录页 ?error：渲染失败提示")
    void loginWithErrorParamShowsFailureHint() throws Exception {
        educated.perform(get("/login").queryParam("error", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("用户名或密码错误")));
    }

    @Test
    @DisplayName("consent 页：client 名、scope 勾选带 i18n 描述、state 回传、空格拼接 scope 拆开")
    void consentRendersClientScopesAndState() throws Exception {
        educated.perform(get("/oauth2/consent")
                        .queryParam("client_id", CLIENT_ID)
                        .queryParam("state", "st-123")
                        .queryParam("scope", "openid profile email"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("演示应用")))
                .andExpect(content().string(containsString("name=\"client_id\"")))
                .andExpect(content().string(containsString("name=\"state\"")))
                .andExpect(content().string(containsString("st-123")))
                .andExpect(content().string(containsString("action=\"/oauth2/consent\"")))
                .andExpect(content().string(containsString("type=\"checkbox\" name=\"scope\"" + " value=\"profile\"")))
                .andExpect(content().string(containsString("确认你的身份标识（openid）")))
                .andExpect(content().string(containsString("读取你的基本资料（如用户名）")))
                .andExpect(content().string(containsString("读取你的邮箱地址")))
                .andExpect(content().string(containsString("同意授权")))
                .andExpect(content().string(containsString("用户授权确认")));
    }

    @Test
    @DisplayName("consent 页：client 查无回退展示 client_id 本身")
    void consentUnknownClientFallsBackToClientId() throws Exception {
        educated.perform(get("/oauth2/consent")
                        .queryParam("client_id", "ghost")
                        .queryParam("state", "st-x")
                        .queryParam("scope", "openid"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ghost")))
                .andExpect(content().string(not(containsString("演示应用"))));
    }

    @Test
    @DisplayName("设备验证页：用户码输入框提交到框架 device_verification 端点")
    void deviceVerifyRendersCodeInput() throws Exception {
        educated.perform(get("/device/verify"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"user_code\"")))
                .andExpect(content().string(containsString("action=\"/oauth2/device_verification\"")))
                .andExpect(content().string(containsString("设备验证")))
                .andExpect(content().string(containsString("用户在本页输入用户码验证")));
    }

    @Test
    @DisplayName("教学开关关闭：三页教学块/流程图/HTTP 日志位全部不渲染")
    void educationalFalseHidesTeachingBlocks() throws Exception {
        plain.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("发生了什么"))))
                .andExpect(content().string(not(containsString("class=\"steps\""))))
                .andExpect(content().string(not(containsString("HTTP 日志"))));
        plain.perform(get("/oauth2/consent")
                        .queryParam("client_id", CLIENT_ID)
                        .queryParam("state", "st-123")
                        .queryParam("scope", "openid"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("发生了什么"))));
        plain.perform(get("/device/verify"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("发生了什么"))));
    }

    private MockMvc buildMockMvc(EducationalFlag flag, MessageSource messageSource) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix(TEMPLATE_PREFIX);
        resolver.setSuffix(".html");
        resolver.setCacheable(false);

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setMessageSource(messageSource);

        ThymeleafViewResolver viewResolver = new ThymeleafViewResolver();
        viewResolver.setTemplateEngine(engine);
        // 不 force 时 Thymeleaf 按请求协商字符集，standalone mock 下会退 ISO-8859-1 把中文写成 '?'——钉死 UTF-8
        viewResolver.setContentType("text/html;charset=UTF-8");
        viewResolver.setForceContentType(true);

        return MockMvcBuilders.standaloneSetup(
                        new LoginController(flag),
                        new ConsentController(mockClients(), new InMemoryScopeCatalog(), messageSource, flag),
                        new DeviceVerifyController(flag))
                .setViewResolvers(viewResolver)
                .setLocaleResolver(new FixedLocaleResolver(Locale.SIMPLIFIED_CHINESE))
                .addFilters(UTF8_RESPONSE_FILTER, new CsrfFilter(new HttpSessionCsrfTokenRepository()))
                .build();
    }

    private MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename(I18N_BASENAME);
        // RbMS 自带 Control 走 PropertyResourceBundle(InputStream)——按 ISO-8859-1 读，必须显式钉 UTF-8
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        return source;
    }

    private RegisteredClientRepository mockClients() {
        RegisteredClient demo = RegisteredClient.withId("1")
                .clientId(CLIENT_ID)
                .clientName("演示应用")
                .clientSecret("secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://client.example.com/callback")
                .scope("openid")
                .scope("profile")
                .scope("email")
                .build();
        RegisteredClientRepository repository = mock(RegisteredClientRepository.class);
        when(repository.findById(CLIENT_ID)).thenReturn(demo);
        when(repository.findById("ghost")).thenReturn(null);
        return repository;
    }
}
