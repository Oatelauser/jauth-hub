package io.github.oatelauser.jauth.core.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import io.github.oatelauser.jauth.core.response.DefaultResponseRenderer;
import io.github.oatelauser.jauth.core.scope.InMemoryScopeCatalog;
import io.github.oatelauser.jauth.core.user.InMemoryUserRepository;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.util.UuidV7;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * consent 页 JSON 状态面（v1.4 B1）：与 SSR 同源装配的自证——个人 plain 态、org 三态（guide/select/
 * selected）、selected 态会话暂存副作用、alreadyGranted 徽标数据、CSRF 字段（CsrfFilter 在场即出值）。
 * 认证面的 401 由 starter 真链测试钉死（core 无自动配置）。
 *
 * @author oatelauser
 */
class ConsentStateControllerTest {

    private static final String ALICE_ID = "018f0000-0000-7000-8000-000000000001";

    private static final String ORG_1 = "018f0000-0000-7000-8000-0000000000a1";

    private static final String ORG_2 = "018f0000-0000-7000-8000-0000000000a2";

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
    @DisplayName("个人客户端 plain 态:无 org 面,scope 排序可勾选,CSRF 字段在场")
    void personalClientPlainState() throws Exception {
        this.mockMvc
                .perform(get("/api/consent")
                        .queryParam("client_id", "personal-app")
                        .queryParam("state", "st-1")
                        .queryParam("scope", "openid profile")
                        .principal(new TestingAuthenticationToken("alice", "n/a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.clientId").value("personal-app"))
                .andExpect(jsonPath("$.data.state").value("st-1"))
                .andExpect(jsonPath("$.data.clientName").value("个人应用演示"))
                .andExpect(jsonPath("$.data.educational").value(true))
                .andExpect(jsonPath("$.data.orgGuide").value(false))
                .andExpect(jsonPath("$.data.orgChoices").value(hasSize(0)))
                .andExpect(jsonPath("$.data.orgBadge").value(nullValue()))
                .andExpect(jsonPath("$.data.scopes").value(hasSize(2)))
                .andExpect(jsonPath("$.data.scopes[0].name").value("openid"))
                .andExpect(jsonPath("$.data.scopes[0].checked").value(true))
                .andExpect(jsonPath("$.data.scopes[0].grantable").value(true))
                .andExpect(jsonPath("$.data.scopes[0].alreadyGranted").value(false))
                .andExpect(jsonPath("$.data.csrfToken").value(not(emptyString())))
                .andExpect(jsonPath("$.data.csrfHeaderName").value("X-CSRF-TOKEN"));
    }

    @Test
    @DisplayName("org 三态之 guide:org 客户端 0 候选 → orgGuide=true,无徽标无选择器")
    void orgClientGuideState() throws Exception {
        // org 客户端(ownerResolver 已登记)但 alice 无任何 APPROVED 安装
        this.mockMvc
                .perform(get("/api/consent")
                        .queryParam("client_id", "org-app")
                        .queryParam("state", "st-2")
                        .queryParam("scope", "openid")
                        .principal(new TestingAuthenticationToken("alice", "n/a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orgGuide").value(true))
                .andExpect(jsonPath("$.data.orgChoices").value(hasSize(0)))
                .andExpect(jsonPath("$.data.orgBadge").value(nullValue()));
    }

    @Test
    @DisplayName("org 三态之 select:多候选 → 选择器条目带 orgId/orgName/href(重入链接),无会话暂存")
    void orgClientSelectState() throws Exception {
        seedOrgWithInstallation(ORG_1, "acme", "rc-org", Set.of("openid"), OrgRole.MEMBER);
        seedOrgWithInstallation(ORG_2, "globex", "rc-org", Set.of("openid", "profile"), OrgRole.MEMBER);
        MockHttpSession session = new MockHttpSession();

        this.mockMvc
                .perform(get("/api/consent")
                        .queryParam("client_id", "org-app")
                        .queryParam("state", "st-3")
                        .queryParam("scope", "openid profile")
                        .principal(new TestingAuthenticationToken("alice", "n/a"))
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orgGuide").value(false))
                .andExpect(jsonPath("$.data.orgChoices").value(hasSize(2)))
                // 按组织名排序:acme 在前
                .andExpect(jsonPath("$.data.orgChoices[0].orgName").value("acme"))
                .andExpect(jsonPath("$.data.orgChoices[0].orgId").value(ORG_1))
                .andExpect(jsonPath("$.data.orgChoices[0].href").value(containsString("org=" + ORG_1)))
                .andExpect(jsonPath("$.data.orgBadge").value(nullValue()));

        assertThat(session.getAttribute(CeilingAwareOAuth2AuthorizationService.CONSENT_ORG_SESSION_KEY_PREFIX + "st-3"))
                .as("选择器态不写会话暂存(与 SSR 同路径)")
                .isNull();
    }

    @Test
    @DisplayName("org 三态之 selected:合法 org 参数 → 徽标 + 会话暂存副作用 + 超 ceiling 禁用")
    void orgClientSelectedStateStashesSession() throws Exception {
        seedOrgWithInstallation(ORG_1, "acme", "rc-org", Set.of("openid"), OrgRole.MEMBER);
        seedOrgWithInstallation(ORG_2, "globex", "rc-org", Set.of("openid", "profile"), OrgRole.MEMBER);
        MockHttpSession session = new MockHttpSession();

        this.mockMvc
                .perform(get("/api/consent")
                        .queryParam("client_id", "org-app")
                        .queryParam("state", "st-4")
                        .queryParam("scope", "openid profile")
                        .queryParam("org", ORG_2)
                        .principal(new TestingAuthenticationToken("alice", "n/a"))
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orgGuide").value(false))
                .andExpect(jsonPath("$.data.orgChoices").value(hasSize(0)))
                .andExpect(jsonPath("$.data.orgBadge").value("globex"))
                .andExpect(
                        jsonPath("$.data.scopes[?(@.name=='openid')].grantable").value(contains(true)))
                .andExpect(jsonPath("$.data.scopes[?(@.name=='profile')].grantable")
                        .value(contains(true)));

        assertThat(session.getAttribute(CeilingAwareOAuth2AuthorizationService.CONSENT_ORG_SESSION_KEY_PREFIX + "st-4"))
                .as("selected 态会话暂存副作用与 SSR 完全同路径发生")
                .isEqualTo(ORG_2);
    }

    @Test
    @DisplayName("单候选隐式 selected:无 org 参数也暂存;ceiling 外 scope grantable=false")
    void singleOrgImplicitSelected() throws Exception {
        seedOrgWithInstallation(ORG_1, "acme", "rc-org", Set.of("openid"), OrgRole.MEMBER);
        MockHttpSession session = new MockHttpSession();

        this.mockMvc
                .perform(get("/api/consent")
                        .queryParam("client_id", "org-app")
                        .queryParam("state", "st-5")
                        .queryParam("scope", "openid profile")
                        .principal(new TestingAuthenticationToken("alice", "n/a"))
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orgBadge").value("acme"))
                .andExpect(
                        jsonPath("$.data.scopes[?(@.name=='openid')].grantable").value(contains(true)))
                .andExpect(jsonPath("$.data.scopes[?(@.name=='profile')].grantable")
                        .value(contains(false)))
                .andExpect(
                        jsonPath("$.data.scopes[?(@.name=='profile')].checked").value(contains(false)));

        assertThat(session.getAttribute(CeilingAwareOAuth2AuthorizationService.CONSENT_ORG_SESSION_KEY_PREFIX + "st-5"))
                .isEqualTo(ORG_1);
    }

    @Test
    @DisplayName("alreadyGranted 徽标数据:既有授权 scope 置位,新 scope 不置位")
    void alreadyGrantedFlagExposed() throws Exception {
        // rc-personal = personal-app 的内部 id(clients() 桩映射);alice 已授过 openid
        this.consentService.save(OAuth2AuthorizationConsent.withId("rc-personal", "alice")
                .scope("openid")
                .build());

        this.mockMvc
                .perform(get("/api/consent")
                        .queryParam("client_id", "personal-app")
                        .queryParam("state", "st-6")
                        .queryParam("scope", "openid profile")
                        .principal(new TestingAuthenticationToken("alice", "n/a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scopes[?(@.name=='openid')].alreadyGranted")
                        .value(contains(true)))
                .andExpect(jsonPath("$.data.scopes[?(@.name=='profile')].alreadyGranted")
                        .value(contains(false)));
    }

    // ------------------------------------------------------------------ 装配小件

    private void seedOrgWithInstallation(
            String orgId, String orgName, String registeredClientId, Set<String> ceiling, OrgRole role) {
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
        ConsentPageAssembler assembler = new ConsentPageAssembler(
                clients(),
                new InMemoryScopeCatalog(),
                messageSource(),
                () -> true,
                new OrgScopeGate(this.userRepository, this.orgRepository, this.installationRepository),
                this.ownerResolver,
                this.consentService);
        return MockMvcBuilders.standaloneSetup(
                        new ConsentController(assembler, TrustSkinFlag.SSR),
                        new ConsentStateController(assembler, new DefaultResponseRenderer()),
                        new DeviceVerifyStateController(() -> true, new DefaultResponseRenderer()))
                .addFilters(new CsrfFilter(new HttpSessionCsrfTokenRepository()))
                .build();
    }

    private MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("io/github/oatelauser/jauth/core/i18n/messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        return source;
    }

    /** rc-org 为组织客户端(按登记表),personal-app 未登记 = 个人语义。 */
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
                        .clientName("个人应用演示")
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .redirectUri("https://placeholder.example.com/callback")
                        .scope("openid")
                        .scope("profile")
                        .build();
            }
        };
    }
}
