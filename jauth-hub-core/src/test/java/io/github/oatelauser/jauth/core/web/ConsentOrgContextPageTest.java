package io.github.oatelauser.jauth.core.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.oatelauser.jauth.core.authorization.CeilingAwareOAuth2AuthorizationService;
import io.github.oatelauser.jauth.core.client.ClientOwner;
import io.github.oatelauser.jauth.core.client.InMemoryClientOwnerResolver;
import io.github.oatelauser.jauth.core.org.InMemoryInstallationRepository;
import io.github.oatelauser.jauth.core.org.InMemoryOrgRepository;
import io.github.oatelauser.jauth.core.org.Installation;
import io.github.oatelauser.jauth.core.org.InstallationStatus;
import io.github.oatelauser.jauth.core.org.Org;
import io.github.oatelauser.jauth.core.org.OrgMember;
import io.github.oatelauser.jauth.core.org.OrgRole;
import io.github.oatelauser.jauth.core.org.OrgScopeGate;
import io.github.oatelauser.jauth.core.scope.InMemoryScopeCatalog;
import io.github.oatelauser.jauth.core.user.InMemoryUserRepository;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.util.UuidV7;
import jakarta.servlet.Filter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.i18n.FixedLocaleResolver;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * consent 页组织客户端三态 DOM 断言（B9，ProtocolPagesTest 同款 standalone MockMvc + 手装 Thymeleaf）：
 * 单候选隐式徽标 + 超 ceiling 禁用、多候选选择器（链接保留原参数）、合法选择写会话暂存并重渲染、0 候选引导态、
 * 个人客户端渲染不变。
 *
 * @author oatelauser
 */
class ConsentOrgContextPageTest {

    private static final String TEMPLATE_PREFIX = "io/github/oatelauser/jauth/core/web/templates/";

    private static final String I18N_BASENAME = "io/github/oatelauser/jauth/core/i18n/messages";

    private static final String ALICE_ID = "018f0000-0000-7000-8000-000000000001";

    private static final String ORG_1 = "018f0000-0000-7000-8000-0000000000a1";

    private static final String ORG_2 = "018f0000-0000-7000-8000-0000000000a2";

    /** 响应编码钉子,理由同 ProtocolPagesTest。 */
    private static final Filter UTF8_RESPONSE_FILTER = (request, response, chain) -> {
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        chain.doFilter(request, response);
    };

    private final InMemoryUserRepository userRepository = new InMemoryUserRepository();

    private final InMemoryOrgRepository orgRepository = new InMemoryOrgRepository();

    private final InMemoryInstallationRepository installationRepository = new InMemoryInstallationRepository();

    private final InMemoryClientOwnerResolver ownerResolver = new InMemoryClientOwnerResolver();
    private final org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationConsentService
            consentService =
                    new org.springframework.security.oauth2.server.authorization
                            .InMemoryOAuth2AuthorizationConsentService();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.userRepository.save(new JauthUser(
                ALICE_ID,
                "alice",
                "encoded-not-a-real-credential",
                null,
                null,
                JauthUser.ROLE_USER,
                JauthUser.STATUS_ACTIVE,
                null,
                Instant.now()));
        this.ownerResolver.put("rc-org", ClientOwner.ofOrg(ORG_1));
        this.mockMvc = buildMockMvc();
    }

    @Test
    @DisplayName("单候选:隐式徽标 + 会话暂存,ceiling 内勾选、超 ceiling 展示但禁用")
    void singleOrgImplicitBadgeWithDisabledBeyondCeiling() throws Exception {
        seedOrgWithInstallation(ORG_1, "acme", "rc-org", java.util.Set.of("openid"), OrgRole.MEMBER);
        MockHttpSession session = new MockHttpSession();

        MvcResult result = this.mockMvc
                .perform(get("/oauth2/consent")
                        .queryParam("client_id", "org-app")
                        .queryParam("state", "st-1")
                        .queryParam("scope", "openid profile")
                        .principal(new TestingAuthenticationToken("alice", "n/a"))
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("class=\"org-badge\"")))
                .andExpect(content().string(containsString("授权组织")))
                .andExpect(content().string(containsString("acme")))
                .andExpect(content().string(containsString("value=\"openid\" checked")))
                .andExpect(content().string(not(containsString("value=\"openid\" disabled"))))
                .andExpect(content().string(containsString("value=\"profile\" disabled")))
                .andExpect(content().string(containsString("value=\"authorize\"")))
                .andReturn();

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("class=\"org-choice\"");
        assertThat(session.getAttribute(CeilingAwareOAuth2AuthorizationService.CONSENT_ORG_SESSION_KEY_PREFIX + "st-1"))
                .isEqualTo(ORG_1);
    }

    @Test
    @DisplayName("多候选:选择器链接保留 client_id/state/scope 并追加 org,不出授权按钮")
    void multiOrgRendersSelectorWithoutAuthorizeAction() throws Exception {
        seedOrgWithInstallation(ORG_1, "acme", "rc-org", java.util.Set.of("openid"), OrgRole.MEMBER);
        seedOrgWithInstallation(ORG_2, "globex", "rc-org", java.util.Set.of("openid", "profile"), OrgRole.MEMBER);

        this.mockMvc
                .perform(get("/oauth2/consent")
                        .queryParam("client_id", "org-app")
                        .queryParam("state", "st-2")
                        .queryParam("scope", "openid profile")
                        .principal(new TestingAuthenticationToken("alice", "n/a")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("请选择本次授权使用的组织上下文")))
                .andExpect(content().string(containsString("acme")))
                .andExpect(content().string(containsString("globex")))
                .andExpect(content().string(containsString("org=" + ORG_1)))
                .andExpect(content().string(containsString("org=" + ORG_2)))
                .andExpect(content().string(containsString("state=st-2")))
                .andExpect(content().string(not(containsString("value=\"authorize\""))));
    }

    @Test
    @DisplayName("选择器重入:合法 org 参数 → 徽标重渲染 + 会话暂存所选 org + 勾选按所选 ceiling")
    void selectorChoiceStashesAndRendersBadge() throws Exception {
        seedOrgWithInstallation(ORG_1, "acme", "rc-org", java.util.Set.of("openid"), OrgRole.MEMBER);
        seedOrgWithInstallation(ORG_2, "globex", "rc-org", java.util.Set.of("openid", "profile"), OrgRole.MEMBER);
        MockHttpSession session = new MockHttpSession();

        this.mockMvc
                .perform(get("/oauth2/consent")
                        .queryParam("client_id", "org-app")
                        .queryParam("state", "st-3")
                        .queryParam("scope", "openid profile")
                        .queryParam("org", ORG_2)
                        .principal(new TestingAuthenticationToken("alice", "n/a"))
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"org-badge\"")))
                .andExpect(content().string(containsString("globex")))
                .andExpect(content().string(not(containsString("value=\"profile\" disabled"))))
                .andExpect(content().string(containsString("value=\"authorize\"")));

        assertThat(session.getAttribute(CeilingAwareOAuth2AuthorizationService.CONSENT_ORG_SESSION_KEY_PREFIX + "st-3"))
                .isEqualTo(ORG_2);
    }

    @Test
    @DisplayName("0 候选:引导态提示需 OWNER 安装,不出授权按钮")
    void zeroCandidatesRendersGuide() throws Exception {
        // org 客户端(ownerResolver 已登记)但 alice 无任何 APPROVED 安装
        this.mockMvc
                .perform(get("/oauth2/consent")
                        .queryParam("client_id", "org-app")
                        .queryParam("state", "st-4")
                        .queryParam("scope", "openid profile")
                        .principal(new TestingAuthenticationToken("alice", "n/a")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"org-guide\"")))
                .andExpect(content().string(containsString("请联系组织管理员发起安装")))
                .andExpect(content().string(not(containsString("value=\"authorize\""))));
    }

    @Test
    @DisplayName("个人客户端:无 org 面,全 scope 可勾选(渲染回归)")
    void personalClientRendersWithoutOrgContext() throws Exception {
        seedOrgWithInstallation(ORG_1, "acme", "rc-org", java.util.Set.of("openid"), OrgRole.MEMBER);

        this.mockMvc
                .perform(get("/oauth2/consent")
                        .queryParam("client_id", "personal-app")
                        .queryParam("state", "st-5")
                        .queryParam("scope", "openid profile")
                        .principal(new TestingAuthenticationToken("alice", "n/a")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("org-badge"))))
                .andExpect(content().string(not(containsString("org-choice"))))
                .andExpect(content().string(not(containsString("org-guide"))))
                .andExpect(content().string(containsString("value=\"openid\" checked")))
                .andExpect(content().string(containsString("value=\"profile\" checked")))
                .andExpect(content().string(containsString("value=\"authorize\"")));
    }

    // ------------------------------------------------------------------ 装配小件

    private void seedOrgWithInstallation(
            String orgId, String orgName, String registeredClientId, java.util.Set<String> ceiling, OrgRole role) {
        this.orgRepository.save(new Org(orgId, orgName, Instant.now()));
        this.orgRepository.saveMember(new OrgMember(orgId, ALICE_ID, role, Instant.now()));
        this.installationRepository.save(new Installation(
                UuidV7.generate().toString(),
                registeredClientId,
                orgId,
                InstallationStatus.APPROVED,
                ceiling,
                ALICE_ID,
                ceiling,
                ALICE_ID,
                Instant.now(),
                Instant.now()));
    }

    private MockMvc buildMockMvc() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix(TEMPLATE_PREFIX);
        resolver.setSuffix(".html");
        resolver.setCacheable(false);

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setMessageSource(messageSource());

        ThymeleafViewResolver viewResolver = new ThymeleafViewResolver();
        viewResolver.setTemplateEngine(engine);
        viewResolver.setContentType("text/html;charset=UTF-8");
        viewResolver.setForceContentType(true);

        return MockMvcBuilders.standaloneSetup(new ConsentController(new ConsentPageAssembler(
                        clients(),
                        new InMemoryScopeCatalog(),
                        messageSource(),
                        () -> false,
                        new OrgScopeGate(this.userRepository, this.orgRepository, this.installationRepository),
                        this.ownerResolver,
                        this.consentService)))
                .setViewResolvers(viewResolver)
                .setLocaleResolver(new FixedLocaleResolver(Locale.SIMPLIFIED_CHINESE))
                .addFilters(UTF8_RESPONSE_FILTER, new CsrfFilter(new HttpSessionCsrfTokenRepository()))
                .build();
    }

    private MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename(I18N_BASENAME);
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        return source;
    }

    /** rc-org/rc-org-2 为组织客户端(按登记表),personal-app 未登记 = 个人语义。 */
    private RegisteredClientRepository clients() {
        return new RegisteredClientRepository() {
            @Override
            public void save(RegisteredClient registeredClient) {
                throw new UnsupportedOperationException("stub");
            }

            @Override
            public RegisteredClient findById(String id) {
                return clientOf(id);
            }

            @Override
            public RegisteredClient findByClientId(String clientId) {
                return "org-app".equals(clientId)
                        ? clientOf("rc-org")
                        : "personal-app".equals(clientId) ? clientOf("rc-personal") : null;
            }

            private RegisteredClient clientOf(String registeredId) {
                return RegisteredClient.withId(registeredId)
                        .clientId(registeredId + "-public")
                        .clientName("组织应用演示")
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .redirectUri("https://placeholder.example.com/callback")
                        .scope("openid")
                        .scope("profile")
                        .build();
            }
        };
    }

    @Test
    @DisplayName("已授权 scope 徽标(老账⑥):既有授权渲染「已授权」徽标,新 scope 无徽标;默认勾选不变")
    void alreadyGrantedScopesBadgedWhileNewOnesPlain() throws Exception {
        // rc-personal = personal-app 的内部 id(clients() 桩映射);alice 已授过 openid
        this.consentService.save(
                org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent.withId(
                                "rc-personal", "alice")
                        .scope("openid")
                        .build());

        this.mockMvc
                .perform(get("/oauth2/consent")
                        .queryParam("client_id", "personal-app")
                        .queryParam("state", "st-6")
                        .queryParam("scope", "openid profile")
                        .principal(new TestingAuthenticationToken("alice", "n/a")))
                .andExpect(status().isOk())
                // openid:已授权徽标;profile:无徽标(增量)
                .andExpect(content().string(containsString("scope-granted")))
                // 勾选态不变:两项仍默认勾选(老账⑥只做知情区分,不动 consent 语义)
                .andExpect(content().string(containsString("value=\"openid\" checked")))
                .andExpect(content().string(containsString("value=\"profile\" checked")));
    }
}
