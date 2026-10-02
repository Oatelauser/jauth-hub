package io.github.oatelauser.jauth.selfservice.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.oatelauser.jauth.core.audit.AuditEvent;
import io.github.oatelauser.jauth.core.org.InMemoryInstallationRepository;
import io.github.oatelauser.jauth.core.org.InMemoryOrgRepository;
import io.github.oatelauser.jauth.core.org.Installation;
import io.github.oatelauser.jauth.core.org.InstallationService;
import io.github.oatelauser.jauth.core.org.Org;
import io.github.oatelauser.jauth.core.org.OrgMember;
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
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 安装审批页 JSON 状态面（v1.5 B1b）：契约（00000 + org 投影 + pending/installations 两分区 + scope 目录
 * i18n 描述 + csrf 对；status 出枚举名且不携带 statusKey——与 SSR 的有意差异）、OWNER 门（MEMBER/局外人/
 * 未知 org 同形 A0508；未认证 A0503）、行装配回归（与 {@link OrgInstallationsController#installationRows}
 * 同源同形）、降级态（域件缺席 installationsSupported=false 不 500）。受测域件全用 core 内存真路径
 * （OrgInstallationsControllerTest 同款）。认证面归部署方 default 链，本测试不设认证面。
 *
 * @author oatelauser
 */
class OrgInstallationsStateControllerTest {

    private static final Instant T0 = Instant.parse("2026-10-02T10:00:00Z");

    private static final String ALICE = "alice";

    private static final String BOB = "bob";

    private static final String CAROL = "carol";

    private static final String UNKNOWN_ORG = "00000000-0000-7000-8000-0000000000ff";

    private UserRepository users;

    private InMemoryOrgRepository orgRepository;

    private InMemoryInstallationRepository installationRepository;

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

        // 两个 client 各走一条安装线：client-1 批准（APPROVED），client-2 留待批（PENDING）
        this.clients = new InMemoryRegisteredClientRepository(
                RegisteredClient.withId("client-1")
                        .clientId("app_orgportal")
                        .clientName("组织门户")
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .redirectUri("https://portal.example.com/cb")
                        .scope("openid")
                        .scope("profile")
                        .build(),
                RegisteredClient.withId("client-2")
                        .clientId("app_reporter")
                        .clientName("报表中枢")
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .redirectUri("https://reporter.example.com/cb")
                        .scope("openid")
                        .build());

        this.orgRepository = new InMemoryOrgRepository();
        this.installationRepository = new InMemoryInstallationRepository();
        OrgService orgService =
                new OrgService(this.orgRepository, this.auditLog::add, Clock.fixed(T0, ZoneOffset.UTC), null);
        InstallationService installationService = new InstallationService(
                this.installationRepository,
                this.orgRepository,
                orgService,
                this.clients,
                this.auditLog::add,
                Clock.fixed(T0, ZoneOffset.UTC));
        this.org = orgService.create("acme", "user-alice");
        this.orgRepository.saveMember(new OrgMember(this.org.id(), "user-bob", OrgRole.MEMBER, T0));
        Installation approved =
                installationService.request("client-1", this.org.id(), Set.of("openid", "profile"), "user-bob");
        installationService.approve(approved.id(), "user-alice", Set.of("openid"));
        installationService.request("client-2", this.org.id(), Set.of("openid"), "user-bob");
    }

    @Test
    @DisplayName(
            "契约：00000 + org 投影 + 两分区（pending=1 PENDING / installations=2）+ status 枚举名无 statusKey + scope 目录 + csrf 对")
    void stateReturnsContractFields() throws Exception {
        MvcResult result = stateApi()
                .perform(get(installationsPath()).principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.educational").value(true))
                .andExpect(jsonPath("$.data.installationsSupported").value(true))
                .andExpect(jsonPath("$.data.org.orgId").value(this.org.id()))
                .andExpect(jsonPath("$.data.org.orgName").value("acme"))
                .andExpect(jsonPath("$.data.pending", hasSize(1)))
                .andExpect(jsonPath("$.data.pending[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data.pending[0].clientName").value("报表中枢"))
                .andExpect(jsonPath("$.data.installations", hasSize(2)))
                .andExpect(jsonPath("$.data.installations[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.data.installations[0].ceilingScopes[0]").value("openid"))
                .andExpect(jsonPath("$.data.installations[0].requestedByName").value("bob"))
                .andExpect(jsonPath("$.data.installations[0].approvedByName").value("alice"))
                .andExpect(jsonPath("$.data.installations[0].statusKey").doesNotExist())
                .andExpect(jsonPath("$.data.scopes[0].name").value("email"))
                .andExpect(jsonPath("$.data.scopes[0].description").value(not(emptyString())))
                .andExpect(jsonPath("$.data.csrfToken").value(not(emptyString())))
                .andExpect(jsonPath("$.data.csrfHeaderName").value("X-CSRF-TOKEN"))
                .andReturn();
        // 行装配回归：状态面 installations 与 SSR 页同源 statics 逐行同形（同仓储同序）
        List<OrgInstallationsController.InstallationRow> expected = OrgInstallationsController.installationRows(
                this.org.id(), this.installationRepository, this.clients, this.users);
        List<Map<String, Object>> installations = JsonPath.read(body(result), "$.data.installations");
        assertThat(installations).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            assertThat(installations.get(i))
                    .containsEntry("id", expected.get(i).id())
                    .containsEntry("clientName", expected.get(i).clientName())
                    .containsEntry("clientLabel", expected.get(i).clientLabel())
                    .containsEntry("status", expected.get(i).status().name())
                    .containsEntry("ceilingScopes", expected.get(i).ceilingScopes())
                    .containsEntry("requestedScopes", expected.get(i).requestedScopes())
                    .containsEntry("createdAt", expected.get(i).createdAt().toString());
        }
    }

    @Test
    @DisplayName("OWNER 门：MEMBER/局外人/未知 org 同形 A0508（先于 org 存在性）；未认证 A0503")
    void nonOwnerGetsA0508() throws Exception {
        stateApi()
                .perform(get(installationsPath()).principal(() -> BOB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("A0508"));
        stateApi()
                .perform(get(installationsPath()).principal(() -> CAROL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("A0508"));
        stateApi()
                .perform(get("/api/selfservice/orgs/" + UNKNOWN_ORG + "/installations")
                        .principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("A0508"));
        stateApi()
                .perform(get(installationsPath()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("A0503"));
    }

    @Test
    @DisplayName("降级：域件缺席 installationsSupported=false、org=null、两分区与 scope 目录空列表（不 500）")
    void degradedStateRendersEmptyInstallations() throws Exception {
        MockMvc unsupported = MockMvcBuilders.standaloneSetup(new OrgInstallationsStateController(
                        null,
                        null,
                        null,
                        null,
                        this.users,
                        this.clients,
                        new InMemoryScopeCatalog(),
                        messageSource(),
                        EducationalFlag.ON,
                        new DefaultResponseRenderer()))
                .setControllerAdvice(new JauthResponseAdvice(new DefaultResponseRenderer()))
                .addFilters(new CsrfFilter(new HttpSessionCsrfTokenRepository()))
                .build();
        unsupported
                .perform(get(installationsPath()).principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.installationsSupported").value(false))
                .andExpect(jsonPath("$.data.org").value(nullValue()))
                .andExpect(jsonPath("$.data.pending").isEmpty())
                .andExpect(jsonPath("$.data.installations").isEmpty())
                .andExpect(jsonPath("$.data.scopes").isEmpty());
    }

    private MockMvc stateApi() {
        return MockMvcBuilders.standaloneSetup(new OrgInstallationsStateController(
                        this.orgService(),
                        new InstallationService(
                                this.installationRepository,
                                this.orgRepository,
                                this.orgService(),
                                this.clients,
                                this.auditLog::add,
                                Clock.fixed(T0, ZoneOffset.UTC)),
                        this.installationRepository,
                        this.orgRepository,
                        this.users,
                        this.clients,
                        new InMemoryScopeCatalog(),
                        messageSource(),
                        EducationalFlag.ON,
                        new DefaultResponseRenderer()))
                .setControllerAdvice(new JauthResponseAdvice(new DefaultResponseRenderer()))
                .addFilters(new CsrfFilter(new HttpSessionCsrfTokenRepository()))
                .build();
    }

    private OrgService orgService() {
        return new OrgService(this.orgRepository, this.auditLog::add, Clock.fixed(T0, ZoneOffset.UTC), null);
    }

    private String installationsPath() {
        return "/api/selfservice/orgs/" + this.org.id() + "/installations";
    }

    private static MessageSource messageSource() {
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

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
