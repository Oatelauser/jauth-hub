package io.github.oatelauser.jauth.selfservice.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.oatelauser.jauth.core.org.Org;
import io.github.oatelauser.jauth.core.org.OrgService;
import io.github.oatelauser.jauth.core.response.DefaultResponseRenderer;
import io.github.oatelauser.jauth.core.response.JauthResponseAdvice;
import io.github.oatelauser.jauth.core.user.InMemoryUserRepository;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.util.UuidV7;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 成员管理面 JSON 单测（v1.3 D3，standalone——sudo 门/渲染各有专属测试面，此处只钉控制器编排：
 * 用户名解析、OWNER 门转发、错误码透传）。审计经 lambda 录制断言服务层打点。
 *
 * @author oatelauser
 */
class OrgMembersControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-02T08:00:00Z");

    private static final String OWNER_NAME = "member-owner";

    private static final String MEMBER_NAME = "member-alice";

    private InMemoryUserRepository users;

    private List<io.github.oatelauser.jauth.core.audit.AuditEvent> auditLog;

    private Org org;

    private MockMvc api;

    @BeforeEach
    void setUp() {
        this.users = new InMemoryUserRepository();
        this.users.save(user(OWNER_NAME));
        this.users.save(user(MEMBER_NAME));
        this.auditLog = new ArrayList<>();
        OrgService orgService = new OrgService(
                new io.github.oatelauser.jauth.core.org.InMemoryOrgRepository(),
                this.auditLog::add,
                Clock.fixed(NOW, ZoneOffset.UTC),
                null);
        this.org = orgService.create(
                "members-r-us", this.users.findByUsername(OWNER_NAME).id());
        OrgMembersController controller =
                new OrgMembersController(orgService, this.users, EducationalFlag.ON, new DefaultResponseRenderer());
        this.api = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new JauthResponseAdvice(new DefaultResponseRenderer()))
                .build();
    }

    @Test
    @DisplayName("添加成员：用户名解析入册恒 MEMBER、审计打点；重复 A0516；未知用户名 B0502")
    void addMemberResolvesUsernameAndAudits() throws Exception {
        String orgId = this.org.id();
        this.api
                .perform(post("/selfservice/orgs/{orgId}/members", orgId)
                        .principal(() -> OWNER_NAME)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + MEMBER_NAME + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.role").value("MEMBER"));
        assertThat(this.auditLog).anyMatch(event -> event.type().wireName().equals("member.added"));

        this.api
                .perform(post("/selfservice/orgs/{orgId}/members", orgId)
                        .principal(() -> OWNER_NAME)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + MEMBER_NAME + "\"}"))
                .andExpect(jsonPath("$.code").value("A0516"));

        this.api
                .perform(post("/selfservice/orgs/{orgId}/members", orgId)
                        .principal(() -> OWNER_NAME)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody-here\"}"))
                .andExpect(jsonPath("$.code").value("B0502"));
    }

    @Test
    @DisplayName("移除与角色翻转：非 OWNER 门 A0508；OWNER 全链 00000")
    void removeAndToggleRoleOwnerGated() throws Exception {
        String orgId = this.org.id();
        String memberId = this.users.findByUsername(MEMBER_NAME).id();
        this.api
                .perform(post("/selfservice/orgs/{orgId}/members", orgId)
                        .principal(() -> OWNER_NAME)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + MEMBER_NAME + "\"}"))
                .andExpect(status().isOk());

        // 非 OWNER（成员本人）翻角色/移除：A0508
        this.api
                .perform(post("/selfservice/orgs/{orgId}/members/{userId}/role", orgId, memberId)
                        .principal(() -> MEMBER_NAME))
                .andExpect(jsonPath("$.code").value("A0508"));
        this.api
                .perform(delete("/selfservice/orgs/{orgId}/members/{userId}", orgId, memberId)
                        .principal(() -> MEMBER_NAME))
                .andExpect(jsonPath("$.code").value("A0508"));

        // OWNER 全链
        this.api
                .perform(post("/selfservice/orgs/{orgId}/members/{userId}/role", orgId, memberId)
                        .principal(() -> OWNER_NAME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("OWNER"));
        this.api
                .perform(delete("/selfservice/orgs/{orgId}/members/{userId}", orgId, memberId)
                        .principal(() -> OWNER_NAME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));
        assertThat(this.auditLog)
                .anyMatch(event -> event.type().wireName().equals("member.role_changed"))
                .anyMatch(event -> event.type().wireName().equals("member.removed"));
    }

    private JauthUser user(String username) {
        return new JauthUser(
                UuidV7.generate().toString(),
                username,
                "{bcrypt}placeholder-not-real",
                null,
                null,
                JauthUser.ROLE_USER,
                JauthUser.STATUS_ACTIVE,
                null,
                NOW);
    }
}
