package io.github.oatelauser.jauth.selfservice.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.oatelauser.jauth.core.client.InMemoryClientOwnerResolver;
import io.github.oatelauser.jauth.core.org.InMemoryOrgRepository;
import io.github.oatelauser.jauth.core.org.Org;
import io.github.oatelauser.jauth.core.org.OrgMember;
import io.github.oatelauser.jauth.core.org.OrgRole;
import io.github.oatelauser.jauth.core.org.OrgService;
import io.github.oatelauser.jauth.core.response.DefaultResponseRenderer;
import io.github.oatelauser.jauth.core.response.JauthResponseAdvice;
import io.github.oatelauser.jauth.core.scope.InMemoryScopeCatalog;
import io.github.oatelauser.jauth.core.token.InMemoryTokenFamilyService;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * org 应用页 JSON 状态面（v1.5 B1b）：契约（00000 + org 投影 orgId/orgName + OwnedApp 行 + csrf 对）、
 * OWNER 门（MEMBER/局外人/未知 org 同形 A0508——控制器入口门先于 org 存在性；未认证 A0503）、行装配回归
 * （与 {@link OwnedAppService#listOrg} 同数据源同形状）、降级态（域件缺席 supported=false 不 500）。受测
 * 服务用 InMemory 真路径（OrgAppsControllerTest 同款）。认证面归部署方 default 链，本测试不设认证面。
 *
 * @author oatelauser
 */
class OrgAppsStateControllerTest {

    private static final Instant T0 = Instant.parse("2026-10-02T10:00:00Z");

    private static final String ALICE = "alice";

    private static final String BOB = "bob";

    private static final String CAROL = "carol";

    /** 局外人在池但与 org 无归属（A0508 的非成员形态须先过"主体在池"）。 */
    private static final String UNKNOWN_ORG = "00000000-0000-7000-8000-0000000000ff";

    private UserRepository users;

    private InMemoryOrgRepository orgRepository;

    private OrgService orgService;

    private InMemoryOwnedAppService ownedAppService;

    private Org org;

    @BeforeEach
    void setUp() {
        this.users = mock(UserRepository.class);
        when(this.users.findByUsername(ALICE)).thenReturn(user("user-alice", ALICE));
        when(this.users.findByUsername(BOB)).thenReturn(user("user-bob", BOB));
        when(this.users.findByUsername(CAROL)).thenReturn(user("user-carol", CAROL));
        this.orgRepository = new InMemoryOrgRepository();
        this.orgService = new OrgService(this.orgRepository, event -> {}, Clock.fixed(T0, ZoneOffset.UTC), null);
        this.ownedAppService = new InMemoryOwnedAppService(
                new InMemoryRegisteredClientRepository(RegisteredClient.withId("seed-platform-1")
                        .clientId("seed-platform")
                        .clientName("platform seed")
                        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                        .build()),
                new InMemoryClientOwnerResolver(),
                new InMemoryTokenFamilyService(),
                new BCryptPasswordEncoder(),
                new InMemoryScopeCatalog(),
                Clock.fixed(T0, ZoneOffset.UTC));
        this.org = this.orgService.create("acme", "user-alice");
        this.ownedAppService.registerOrg(this.org.id(), "组织门户", Set.of("https://portal.example.com/cb"), true);
    }

    @Test
    @DisplayName("契约：00000 + org 投影（orgId/orgName）+ OwnedApp 行与 listOrg 同源同形 + csrf 对")
    void stateReturnsContractFields() throws Exception {
        MvcResult result = stateApi()
                .perform(get(appsPath()).principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.educational").value(true))
                .andExpect(jsonPath("$.data.orgAppsSupported").value(true))
                .andExpect(jsonPath("$.data.org.orgId").value(this.org.id()))
                .andExpect(jsonPath("$.data.org.orgName").value("acme"))
                .andExpect(jsonPath("$.data.csrfToken").value(not(emptyString())))
                .andExpect(jsonPath("$.data.csrfHeaderName").value("X-CSRF-TOKEN"))
                .andReturn();
        // 行装配回归：状态面 apps 与服务直出（SSR 页模型同源）逐行同形
        List<OwnedAppService.OwnedApp> expected = this.ownedAppService.listOrg(this.org.id());
        List<Map<String, Object>> apps = JsonPath.read(body(result), "$.data.apps");
        assertThat(apps).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            assertThat(apps.get(i))
                    .containsEntry("id", expected.get(i).id())
                    .containsEntry("clientId", expected.get(i).clientId())
                    .containsEntry("name", expected.get(i).name())
                    .containsEntry("confidential", expected.get(i).confidential())
                    .containsEntry("redirectUris", expected.get(i).redirectUris())
                    .containsEntry("createdAt", expected.get(i).createdAt().toString());
        }
    }

    @Test
    @DisplayName("OWNER 门：MEMBER/局外人/未知 org 同形 A0508（先于 org 存在性）；未认证 A0503")
    void nonOwnerGetsA0508() throws Exception {
        this.orgRepository.saveMember(new OrgMember(this.org.id(), "user-bob", OrgRole.MEMBER, T0));
        stateApi()
                .perform(get(appsPath()).principal(() -> BOB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("A0508"));
        stateApi()
                .perform(get(appsPath()).principal(() -> CAROL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("A0508"));
        stateApi()
                .perform(get("/api/selfservice/orgs/" + UNKNOWN_ORG + "/apps").principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("A0508"));
        stateApi()
                .perform(get(appsPath()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("A0503"));
    }

    @Test
    @DisplayName("降级：域件缺席 orgAppsSupported=false、org=null、apps 空列表（不 500）")
    void degradedStateRendersEmptyApps() throws Exception {
        MockMvc unsupported = MockMvcBuilders.standaloneSetup(new OrgAppsStateController(
                        null, null, null, this.users, EducationalFlag.ON, new DefaultResponseRenderer()))
                .setControllerAdvice(new JauthResponseAdvice(new DefaultResponseRenderer()))
                .addFilters(new CsrfFilter(new HttpSessionCsrfTokenRepository()))
                .build();
        unsupported
                .perform(get(appsPath()).principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.orgAppsSupported").value(false))
                .andExpect(jsonPath("$.data.org").value(nullValue()))
                .andExpect(jsonPath("$.data.apps").isEmpty());
    }

    private MockMvc stateApi() {
        return MockMvcBuilders.standaloneSetup(new OrgAppsStateController(
                        this.ownedAppService,
                        this.orgService,
                        this.orgRepository,
                        this.users,
                        EducationalFlag.ON,
                        new DefaultResponseRenderer()))
                .setControllerAdvice(new JauthResponseAdvice(new DefaultResponseRenderer()))
                .addFilters(new CsrfFilter(new HttpSessionCsrfTokenRepository()))
                .build();
    }

    private String appsPath() {
        return "/api/selfservice/orgs/" + this.org.id() + "/apps";
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
