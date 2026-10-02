package io.github.oatelauser.jauth.selfservice.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.oatelauser.jauth.core.org.InMemoryOrgRepository;
import io.github.oatelauser.jauth.core.org.Org;
import io.github.oatelauser.jauth.core.org.OrgMember;
import io.github.oatelauser.jauth.core.org.OrgRepository;
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
 * org 成员管理页 JSON 状态面（v1.5 B1b）：契约（00000 + org 投影 orgId/orgName + members 行 role 出枚举名 +
 * csrf 对）、OWNER 门（MEMBER/局外人/未知 org 同形 A0508——服务层单点门，org 存在性不泄露；未认证 A0503）、
 * 行装配回归（与 {@link OrgMembersController#memberRows} 同源同形）、降级态（服务缺席 membersSupported=false
 * 不 500）。受测服务用 core 内存真路径（OrgMembersControllerTest 同款）。认证面归部署方 default 链，本测试
 * 不设认证面。
 *
 * @author oatelauser
 */
class OrgMembersStateControllerTest {

    private static final Instant T0 = Instant.parse("2026-10-02T10:00:00Z");

    private static final String ALICE = "alice";

    private static final String BOB = "bob";

    private static final String CAROL = "carol";

    private static final String UNKNOWN_ORG = "00000000-0000-7000-8000-0000000000ff";

    private UserRepository users;

    private JauthUser alice;

    private JauthUser bob;

    private InMemoryOrgRepository orgRepository;

    private OrgService orgService;

    private Org org;

    @BeforeEach
    void setUp() {
        this.alice = user("user-alice", ALICE);
        this.bob = user("user-bob", BOB);
        this.users = mock(UserRepository.class);
        when(this.users.findByUsername(ALICE)).thenReturn(this.alice);
        when(this.users.findByUsername(BOB)).thenReturn(this.bob);
        when(this.users.findByUsername(CAROL)).thenReturn(user("user-carol", CAROL));
        when(this.users.findById("user-alice")).thenReturn(this.alice);
        when(this.users.findById("user-bob")).thenReturn(this.bob);
        this.orgRepository = new InMemoryOrgRepository();
        this.orgService = new OrgService(this.orgRepository, event -> {}, Clock.fixed(T0, ZoneOffset.UTC), null);
        this.org = this.orgService.create("acme", "user-alice");
        this.orgRepository.saveMember(new OrgMember(this.org.id(), "user-bob", OrgRole.MEMBER, T0));
    }

    @Test
    @DisplayName("契约：00000 + org 投影（orgId/orgName，SSR 页没有的增量）+ members 行（role 枚举名）+ csrf 对 + 行装配同源")
    void stateReturnsContractFields() throws Exception {
        MvcResult result = stateApi(this.orgService, this.orgRepository)
                .perform(get(membersPath()).principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.educational").value(true))
                .andExpect(jsonPath("$.data.membersSupported").value(true))
                .andExpect(jsonPath("$.data.org.orgId").value(this.org.id()))
                .andExpect(jsonPath("$.data.org.orgName").value("acme"))
                .andExpect(jsonPath("$.data.csrfToken").value(not(emptyString())))
                .andExpect(jsonPath("$.data.csrfHeaderName").value("X-CSRF-TOKEN"))
                .andReturn();
        // 行装配回归：状态面 members 与 SSR 页同源 statics 同形（userId/username/role 枚举名/createdAt ISO）
        assertThat((List<Map<String, Object>>) JsonPath.read(body(result), "$.data.members"))
                .extracting(
                        row -> row.get("userId"),
                        row -> row.get("username"),
                        row -> row.get("role"),
                        row -> row.get("createdAt"))
                .containsExactlyInAnyOrder(
                        tuple("user-alice", ALICE, "OWNER", T0.toString()),
                        tuple("user-bob", BOB, "MEMBER", T0.toString()));
    }

    @Test
    @DisplayName("OWNER 门：MEMBER/局外人/未知 org 同形 A0508（服务层单点，org 存在性不泄露）；未认证 A0503")
    void nonOwnerGetsA0508() throws Exception {
        stateApi(this.orgService, this.orgRepository)
                .perform(get(membersPath()).principal(() -> BOB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("A0508"));
        stateApi(this.orgService, this.orgRepository)
                .perform(get(membersPath()).principal(() -> CAROL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("A0508"));
        stateApi(this.orgService, this.orgRepository)
                .perform(
                        get("/api/selfservice/orgs/" + UNKNOWN_ORG + "/members").principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("A0508"));
        stateApi(this.orgService, this.orgRepository)
                .perform(get(membersPath()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("A0503"));
    }

    @Test
    @DisplayName("降级：服务缺席 membersSupported=false、org=null、members 空列表（不 500）")
    void degradedStateRendersEmptyMembers() throws Exception {
        stateApi(null, this.orgRepository)
                .perform(get(membersPath()).principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.membersSupported").value(false))
                .andExpect(jsonPath("$.data.org").value(nullValue()))
                .andExpect(jsonPath("$.data.members").isEmpty());
        stateApi(this.orgService, null)
                .perform(get(membersPath()).principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.membersSupported").value(false));
    }

    /** 状态面 standalone（advice 手挂 + CSRF 过滤器，同家族测试惯例）。 */
    private MockMvc stateApi(@Nullable OrgService service, @Nullable OrgRepository repository) {
        return MockMvcBuilders.standaloneSetup(new OrgMembersStateController(
                        service, repository, this.users, EducationalFlag.ON, new DefaultResponseRenderer()))
                .setControllerAdvice(new JauthResponseAdvice(new DefaultResponseRenderer()))
                .addFilters(new CsrfFilter(new HttpSessionCsrfTokenRepository()))
                .build();
    }

    private String membersPath() {
        return "/api/selfservice/orgs/" + this.org.id() + "/members";
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
