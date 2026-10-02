package io.github.oatelauser.jauth.selfservice.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 我的组织页 JSON 状态面（v1.5 B1a）：契约（00000 + OrgMembership 行、role 出枚举名 + csrf 对）、降级态
 * （服务缺席 orgsSupported=false、非池主体空列表）。受测服务用 core 内存件（InMemoryOrgRepository +
 * OrgService 真路径，MyOrgsControllerTest 同款）。认证面归部署方 default 链，本测试不设认证面。
 *
 * @author oatelauser
 */
class MyOrgsStateControllerTest {

    private static final Instant T0 = Instant.parse("2026-10-02T10:00:00Z");

    private static final String ALICE = "alice";

    private UserRepository users;

    private InMemoryOrgRepository orgRepository;

    private OrgService orgService;

    @BeforeEach
    void setUp() {
        this.users = mock(UserRepository.class);
        when(this.users.findByUsername(ALICE)).thenReturn(user());
        this.orgRepository = new InMemoryOrgRepository();
        this.orgService = new OrgService(this.orgRepository, event -> {}, Clock.fixed(T0, ZoneOffset.UTC), null);
    }

    @Test
    @DisplayName("契约：00000 + educational/orgsSupported + 归属行（orgId/orgName/role 枚举名 OWNER、MEMBER）+ csrf 对")
    void stateReturnsContractFields() throws Exception {
        Org owned = this.orgService.create("acme", "user-alice");
        this.orgRepository.save(new Org("other-org-1", "globex", T0));
        this.orgRepository.saveMember(new OrgMember("other-org-1", "user-alice", OrgRole.MEMBER, T0));

        MvcResult result = stateApi(this.orgService)
                .perform(get("/api/selfservice/my-orgs").principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.educational").value(true))
                .andExpect(jsonPath("$.data.orgsSupported").value(true))
                .andExpect(jsonPath("$.data.csrfToken").value(not(emptyString())))
                .andExpect(jsonPath("$.data.csrfHeaderName").value("X-CSRF-TOKEN"))
                .andReturn();
        // 行序不钉死（findByUser 实现序），按内容断言两行（role 出枚举名）
        List<Map<String, Object>> orgs = JsonPath.read(body(result), "$.data.orgs");
        assertThat(orgs)
                .extracting(org -> org.get("orgId"), org -> org.get("orgName"), org -> org.get("role"))
                .containsExactlyInAnyOrder(
                        tuple(owned.id(), "acme", "OWNER"), tuple("other-org-1", "globex", "MEMBER"));
    }

    @Test
    @DisplayName("降级：服务缺席 orgsSupported=false；非池主体与未认证同空列表态（同 SSR 页取舍，不 500）")
    void degradedStatesRenderEmptyOrgs() throws Exception {
        stateApi(null)
                .perform(get("/api/selfservice/my-orgs").principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orgsSupported").value(false))
                .andExpect(jsonPath("$.data.orgs").isEmpty());
        stateApi(this.orgService)
                .perform(get("/api/selfservice/my-orgs").principal(() -> "not-in-pool"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orgsSupported").value(true))
                .andExpect(jsonPath("$.data.orgs").isEmpty());
    }

    /** 状态面 standalone（advice 手挂 + CSRF 过滤器，同家族测试惯例）。 */
    private MockMvc stateApi(@Nullable OrgService service) {
        return MockMvcBuilders.standaloneSetup(new MyOrgsStateController(
                        service, this.users, EducationalFlag.ON, new DefaultResponseRenderer()))
                .setControllerAdvice(new JauthResponseAdvice(new DefaultResponseRenderer()))
                .addFilters(new CsrfFilter(new HttpSessionCsrfTokenRepository()))
                .build();
    }

    private static JauthUser user() {
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

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
