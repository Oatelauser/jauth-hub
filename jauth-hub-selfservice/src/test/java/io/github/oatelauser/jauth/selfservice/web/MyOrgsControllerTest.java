package io.github.oatelauser.jauth.selfservice.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.oatelauser.jauth.core.audit.AuditEvent;
import io.github.oatelauser.jauth.core.org.InMemoryOrgRepository;
import io.github.oatelauser.jauth.core.org.OrgService;
import io.github.oatelauser.jauth.core.response.DefaultResponseRenderer;
import io.github.oatelauser.jauth.core.response.JauthResponseAdvice;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
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
 * 我的组织页（B11）：页面路由 302 到 SPA 皮 + 创建 JSON 面（v1.5 B5b 重写）
 * （成功建号自动 OWNER、重名 A0506、名空/超长 A0501/A0502、未认证 A0503、服务缺席 A0504）。受测服务用 core
 * 内存件（InMemoryOrgRepository + OrgService 真路径）——页面两存储模式同契约。
 *
 * @author oatelauser
 */
class MyOrgsControllerTest {

    private static final Instant T0 = Instant.parse("2026-09-30T10:00:00Z");

    private static final String ALICE = "alice";

    private UserRepository users;

    private OrgService orgService;

    private InMemoryOrgRepository orgRepository;

    private final List<AuditEvent> auditLog = new ArrayList<>();

    @BeforeEach
    void setUp() {
        this.users = mock(UserRepository.class);
        when(this.users.findByUsername(ALICE)).thenReturn(alice());
        this.orgRepository = new InMemoryOrgRepository();
        this.orgService = new OrgService(this.orgRepository, this.auditLog::add, Clock.fixed(T0, ZoneOffset.UTC), null);
    }

    @Test
    @DisplayName("页面路由：/selfservice/my-orgs 无条件 302 到 SPA 皮")
    void pageRouteRedirectsToFront() throws Exception {
        api().perform(get("/selfservice/my-orgs"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/front/selfservice/my-orgs"));
    }

    @Test
    @DisplayName("创建 JSON：成功（创建者自动 OWNER，orgId 回填）；重名 A0506")
    void createMakesCreatorOwnerAndRejectsDuplicateName() throws Exception {
        api().perform(post("/selfservice/my-orgs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"acme\"}")
                        .principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.name").value("acme"));
        assertThat(this.orgService.isOwner(orgIdByName("acme"), "user-alice")).isTrue();

        api().perform(post("/selfservice/my-orgs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"acme\"}")
                        .principal(() -> ALICE))
                .andExpect(jsonPath("$.code").value("A0506"));
    }

    @Test
    @DisplayName("创建校验：名空 A0501、超 50 字符 A0502；未认证 A0503；服务缺席 A0504")
    void createValidatesNameAndGates() throws Exception {
        api().perform(post("/selfservice/my-orgs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \"}")
                        .principal(() -> ALICE))
                .andExpect(jsonPath("$.code").value("A0501"));
        api().perform(post("/selfservice/my-orgs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + "x".repeat(51) + "\"}")
                        .principal(() -> ALICE))
                .andExpect(jsonPath("$.code").value("A0502"));
        api().perform(post("/selfservice/my-orgs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"acme\"}"))
                .andExpect(jsonPath("$.code").value("A0503"));
        MockMvc missing = MockMvcBuilders.standaloneSetup(
                        new MyOrgsController(null, this.users, new DefaultResponseRenderer()))
                .setControllerAdvice(jauthAdvice())
                .setViewResolvers(new org.springframework.web.servlet.view.InternalResourceViewResolver())
                .build();
        missing.perform(post("/selfservice/my-orgs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"acme\"}")
                        .principal(() -> ALICE))
                .andExpect(jsonPath("$.code").value("A0504"));
    }

    private static JauthResponseAdvice jauthAdvice() {
        return new JauthResponseAdvice(new DefaultResponseRenderer());
    }

    /** JSON 面（advice 手挂：JauthException → SPI 失败体，生产由 starter 装配；视图解析器供 redirect 断言）。 */
    private MockMvc api() {
        return MockMvcBuilders.standaloneSetup(
                        new MyOrgsController(this.orgService, this.users, new DefaultResponseRenderer()))
                .setControllerAdvice(jauthAdvice())
                .setViewResolvers(new org.springframework.web.servlet.view.InternalResourceViewResolver())
                .build();
    }

    private String orgIdByName(String name) {
        return this.orgRepository.findByName(name).id();
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
