package io.github.oatelauser.jauth.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.oatelauser.jauth.app.JauthHubAppApplication;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 用户管理面集成测试（B12）：页面 DOM（列表/徽标/建号表单）+ JSON 全操作（建号/撞名/校验/改角色/停启用/
 * 重置密码）+ 门控三态（未认证 401/302、非超管 403、自操作拒 A0513）+ 停用挡登录闭环。
 *
 * <p>凭据纪律：测试口令均为占位常量（非真实凭据）；重置密码响应不回显明文是验收四条之一。
 *
 * @author oatelauser
 */
@SpringBootTest(classes = JauthHubAppApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:jauth-admin-users-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "jauth-hub.bootstrap.superadmin.username=superadmin",
            "jauth-hub.bootstrap.superadmin.password=super-secret-placeholder"
        })
class AdminUsersIntegrationTest {

    private static final String ADMIN_USERNAME = "superadmin";

    /** 建号初始口令（占位值，非真实凭据）。 */
    private static final String INITIAL_PASSWORD = "initial-pass-placeholder";

    /** 重置后的口令（占位值）。 */
    private static final String RESET_PASSWORD = "reset-pass-placeholder";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("管理页 DOM：列表行/徽标/建号表单齐备（含 USER 行与显示名回显）")
    void adminUsersPageRendersListBadgesAndCreateForm() throws Exception {
        createUser("page-bob", "鲍勃");

        this.mockMvc
                .perform(get("/admin/users").with(superAdmin()).locale(Locale.SIMPLIFIED_CHINESE))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("用户管理")))
                .andExpect(content().string(containsString("name=\"username\"")))
                .andExpect(content().string(containsString("name=\"password\"")))
                .andExpect(content().string(containsString("name=\"displayName\"")))
                .andExpect(content().string(containsString("page-bob")))
                .andExpect(content().string(containsString("鲍勃")))
                .andExpect(content().string(containsString("超管")))
                .andExpect(content().string(containsString("用户")))
                .andExpect(content().string(containsString("启用")))
                .andExpect(content().string(containsString("改角色")))
                .andExpect(content().string(containsString("停用/启用")))
                .andExpect(content().string(containsString("重置密码")))
                .andExpect(content().string(containsString("发生了什么")));
    }

    @Test
    @DisplayName("建号：落库 USER/ACTIVE、bcrypt 哈希可校验、响应无凭据材料")
    void createUserPersistsHashedAndReturnsSummary() throws Exception {
        this.mockMvc
                .perform(adminJson(post("/api/admin/users"), createBody("create-alice", "爱丽丝", INITIAL_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.username").value("create-alice"))
                .andExpect(jsonPath("$.data.displayName").value("爱丽丝"))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.password").doesNotExist());

        JauthUser stored = this.userRepository.findByUsername("create-alice");
        assertThat(stored).as("建号已落库").isNotNull();
        assertThat(stored.role()).isEqualTo(JauthUser.ROLE_USER);
        assertThat(this.passwordEncoder.matches(INITIAL_PASSWORD, stored.passwordHash()))
                .as("DelegatingPasswordEncoder 编码的口令可校验")
                .isTrue();
    }

    @Test
    @DisplayName("建号撞名：A0512；用户名空 A0501；口令过短与显示名超长 A0502")
    void createUserValidatesInput() throws Exception {
        this.mockMvc
                .perform(adminJson(post("/api/admin/users"), createBody("dup-carol", null, INITIAL_PASSWORD)))
                .andExpect(jsonPath("$.code").value("00000"));

        this.mockMvc
                .perform(adminJson(post("/api/admin/users"), createBody("dup-carol", null, "another-pass-placeholder")))
                .andExpect(jsonPath("$.code").value("A0512"));

        this.mockMvc
                .perform(adminJson(post("/api/admin/users"), createBody("   ", null, INITIAL_PASSWORD)))
                .andExpect(jsonPath("$.code").value("A0501"));

        this.mockMvc
                .perform(adminJson(post("/api/admin/users"), createBody("short-dave", null, "short")))
                .andExpect(jsonPath("$.code").value("A0502"));

        this.mockMvc
                .perform(
                        adminJson(post("/api/admin/users"), createBody("long-erin", "x".repeat(101), INITIAL_PASSWORD)))
                .andExpect(jsonPath("$.code").value("A0502"));
    }

    @Test
    @DisplayName("改角色：USER↔SUPERADMIN 翻转落库；自操作拒 A0513；目标不存在 B0502")
    void toggleRoleFlipsRejectsSelfAndMissingTarget() throws Exception {
        String targetId = createUser("role-frank", null);

        this.toggle(targetId + "/role")
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.role").value("SUPERADMIN"));
        assertThat(this.userRepository.findById(targetId).role()).isEqualTo(JauthUser.ROLE_SUPERADMIN);

        this.toggle(targetId + "/role").andExpect(jsonPath("$.data.role").value("USER"));

        String ownId = this.userRepository.findByUsername(ADMIN_USERNAME).id();
        this.toggle(ownId + "/role").andExpect(jsonPath("$.code").value("A0513"));
        assertThat(this.userRepository.findByUsername(ADMIN_USERNAME).role())
                .as("自降被拒后角色不变")
                .isEqualTo(JauthUser.ROLE_SUPERADMIN);

        this.toggle("00000000-0000-7000-8000-0000000000ff/role")
                .andExpect(jsonPath("$.code").value("B0502"));
    }

    @Test
    @DisplayName("停用挡登录（status→enabled 接线）：自停用拒 A0513、停用后表单登录错误回跳、启用恢复")
    void toggleStatusBlocksAndRestoresLogin() throws Exception {
        createUser("status-gwen", null);
        this.mockMvc.perform(formLogin("status-gwen", INITIAL_PASSWORD)).andExpect(loginSucceeds());

        String targetId = this.userRepository.findByUsername("status-gwen").id();
        String ownId = this.userRepository.findByUsername(ADMIN_USERNAME).id();

        this.toggle(ownId + "/status").andExpect(jsonPath("$.code").value("A0513"));

        this.toggle(targetId + "/status").andExpect(jsonPath("$.data.status").value("DISABLED"));
        this.mockMvc.perform(formLogin("status-gwen", INITIAL_PASSWORD)).andExpect(loginFails());

        this.toggle(targetId + "/status").andExpect(jsonPath("$.data.status").value("ACTIVE"));
        this.mockMvc.perform(formLogin("status-gwen", INITIAL_PASSWORD)).andExpect(loginSucceeds());
    }

    @Test
    @DisplayName("重置密码：明文不出现在响应、旧口令失效、新口令可登录")
    void resetPasswordRotatesCredentialWithoutEcho() throws Exception {
        createUser("reset-hank", null);
        String targetId = this.userRepository.findByUsername("reset-hank").id();

        this.mockMvc
                .perform(adminJson(
                        post("/api/admin/users/" + targetId + "/password"),
                        "{\"password\":\"%s\"}".formatted(RESET_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.username").value("reset-hank"))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(content().string(not(containsString(RESET_PASSWORD))));

        this.mockMvc.perform(formLogin("reset-hank", RESET_PASSWORD)).andExpect(loginSucceeds());
        this.mockMvc.perform(formLogin("reset-hank", INITIAL_PASSWORD)).andExpect(loginFails());
    }

    @Test
    @DisplayName("门控：非超管页面与 JSON 全 403；未认证浏览器 302 /login、API 401")
    void accessControlStates() throws Exception {
        this.mockMvc
                .perform(get("/admin/users").with(user("plain-user").roles("USER")))
                .andExpect(status().isForbidden());
        this.mockMvc
                .perform(post("/api/admin/users")
                        .with(user("plain-user").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("forbidden-ivy", null, INITIAL_PASSWORD)))
                .andExpect(status().isForbidden());

        this.mockMvc
                .perform(get("/admin/users").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(result ->
                        assertThat(result.getResponse().getHeader("Location")).contains("/login"));
        this.mockMvc
                .perform(post("/api/admin/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("anon-jack", null, INITIAL_PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    /** 翻转动作（role/status）：路径取 id 后缀，统一超管 + CSRF + 空 JSON 体。 */
    private ResultActions toggle(String idSuffix) throws Exception {
        return this.mockMvc.perform(adminJson(post("/api/admin/users/" + idSuffix), "{}"));
    }

    private static RequestPostProcessor superAdmin() {
        return user(ADMIN_USERNAME).roles("SUPER_ADMIN");
    }

    /** 建号辅助：成功即返回新用户 id（后续行内操作复用）。 */
    private String createUser(String username, String displayName) throws Exception {
        this.mockMvc
                .perform(adminJson(post("/api/admin/users"), createBody(username, displayName, INITIAL_PASSWORD)))
                .andExpect(jsonPath("$.code").value("00000"));
        return this.userRepository.findByUsername(username).id();
    }

    /** 超管 JSON 请求统一形态。 */
    private static MockHttpServletRequestBuilder adminJson(MockHttpServletRequestBuilder builder, String body) {
        return builder.with(user(ADMIN_USERNAME).roles("SUPER_ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static String createBody(String username, String displayName, String password) {
        String displayJson = displayName == null ? "" : ",\"displayName\":\"" + displayName + "\"";
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"" + displayJson + "}";
    }

    private static MockHttpServletRequestBuilder formLogin(String username, String password) {
        return post("/login").with(csrf()).param("username", username).param("password", password);
    }

    private static ResultMatcher loginSucceeds() {
        return result -> {
            assertThat(result.getResponse().getStatus()).as("登录成功应 3xx").isIn(301, 302, 303, 307);
            assertThat(result.getResponse().getHeader("Location")).doesNotContain("error");
        };
    }

    private static ResultMatcher loginFails() {
        return result -> {
            assertThat(result.getResponse().getStatus()).as("登录失败应 3xx 回登录页").isIn(301, 302, 303, 307);
            assertThat(result.getResponse().getHeader("Location")).contains("error");
        };
    }

    @Test
    @DisplayName("用户列表分页（老账⑦）：size=2 三用户 → 两页，导航与合计渲染")
    void usersListPaginated() throws Exception {
        for (int i = 1; i <= 3; i++) {
            this.mockMvc
                    .perform(post("/api/admin/users")
                            .with(user(ADMIN_USERNAME).roles("SUPER_ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"page-user-" + i + "\",\"password\":\"paged-pass-placeholder\"}"))
                    .andExpect(status().isOk());
        }
        this.mockMvc
                .perform(get("/admin/users")
                        .queryParam("size", "2")
                        .locale(java.util.Locale.SIMPLIFIED_CHINESE)
                        .with(user(ADMIN_USERNAME).roles("SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("page-user")))
                .andExpect(content().string(containsString("下一页")));
        this.mockMvc
                .perform(get("/admin/users")
                        .queryParam("size", "2")
                        .queryParam("page", "2")
                        .locale(java.util.Locale.SIMPLIFIED_CHINESE)
                        .with(user(ADMIN_USERNAME).roles("SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("上一页")));
    }
}
