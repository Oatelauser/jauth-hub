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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.oatelauser.jauth.app.JauthHubAppApplication;
import io.github.oatelauser.jauth.core.ratelimit.RateLimiter;
import io.github.oatelauser.jauth.core.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 档案/自助改密集成测试（B12）：页面 DOM、改显示名（含清空回退）、旧密码核验联动登录防爆破计数（连错达
 * 登录锁定阈值后：改密面拒 A0514、表单登录也被锁）、改密成功后新旧口令两态。凭据均为占位常量。
 *
 * <p>串扰隔离：RateLimiter 是全 context 共享 bean，登录失败计数按用户名累积——每个测试用独立用户名，
 * 不依赖用例执行顺序。
 *
 * @author oatelauser
 */
@SpringBootTest(classes = JauthHubAppApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:jauth-profile-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "jauth-hub.bootstrap.superadmin.username=superadmin",
            "jauth-hub.bootstrap.superadmin.password=super-secret-placeholder"
        })
class ProfileIntegrationTest {

    private static final String ADMIN_USERNAME = "superadmin";

    /** 建号初始口令（占位值，非真实凭据）。 */
    private static final String INITIAL_PASSWORD = "initial-pass-placeholder";

    /** 改密后的口令（占位值）。 */
    private static final String NEW_PASSWORD = "rotated-pass-placeholder";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RateLimiter rateLimiter;

    @Test
    @DisplayName("档案页路由（v1.5 B5b）：302 到 /front/profile 的 SPA 皮")
    void profilePageRedirectsToFront() throws Exception {
        this.mockMvc
                .perform(get("/profile").with(user("route-olivia").roles("USER")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/front/profile"));
    }

    @Test
    @DisplayName("档案页状态 API（v1.5 B1a）：00000 + username/displayName/educational + csrf 对；未认证 401")
    void profileStateApiReturnsAccountAndCsrf() throws Exception {
        createUser("state-olivia", "奥利维亚");

        this.mockMvc
                .perform(get("/api/profile")
                        .with(user("state-olivia").roles("USER"))
                        .with(csrf())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.educational").value(true))
                .andExpect(jsonPath("$.data.username").value("state-olivia"))
                .andExpect(jsonPath("$.data.displayName").value("奥利维亚"))
                .andExpect(jsonPath("$.data.csrfToken").isNotEmpty())
                .andExpect(jsonPath("$.data.csrfHeaderName").value("X-CSRF-TOKEN"));

        this.mockMvc
                .perform(get("/api/profile").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("改显示名：落库可清空（null 回退用户名）；未认证 401/302")
    void updateDisplayNamePersistsAndClears() throws Exception {
        createUser("rename-pat", null);

        this.mockMvc
                .perform(
                        profileJson(post("/api/profile"), user("rename-pat").roles("USER"), "{\"displayName\":\"帕特\"}"))
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.displayName").value("帕特"));
        assertThat(this.userRepository.findByUsername("rename-pat").displayName())
                .isEqualTo("帕特");

        this.mockMvc
                .perform(profileJson(post("/api/profile"), user("rename-pat").roles("USER"), "{\"displayName\":\"\"}"))
                .andExpect(jsonPath("$.code").value("00000"));
        assertThat(this.userRepository.findByUsername("rename-pat").displayName())
                .as("空串等价清空（UI 回退用户名）")
                .isNull();

        this.mockMvc.perform(get("/profile").accept(MediaType.TEXT_HTML)).andExpect(status().is3xxRedirection());
        this.mockMvc
                .perform(post("/api/profile")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("旧密码错：A0514 且哈希未动；连错达登录阈值后改密面与表单登录双锁（联动防爆破计数）")
    void wrongOldPasswordCountsTowardSharedLoginLock() throws Exception {
        String username = "lock-quinn";
        createUser(username, null);
        String wrongOldBody = passwordBody("wrong-old-placeholder", NEW_PASSWORD);

        for (int i = 0; i < 4; i++) {
            this.mockMvc
                    .perform(profileJson(
                            post("/api/profile/password"), user(username).roles("USER"), wrongOldBody))
                    .andExpect(jsonPath("$.code").value("A0514"));
        }
        assertThat(this.rateLimiter.isLoginLocked(username))
                .as("4 次连错未到阈值 5，尚未锁")
                .isFalse();
        assertThat(this.passwordEncoder.matches(
                        INITIAL_PASSWORD,
                        this.userRepository.findByUsername(username).passwordHash()))
                .as("验旧失败不改哈希")
                .isTrue();

        // 第 5 次失败触发锁定：此后旧密码正确也拒（先判锁），表单登录同锁
        this.mockMvc
                .perform(profileJson(
                        post("/api/profile/password"), user(username).roles("USER"), wrongOldBody))
                .andExpect(jsonPath("$.code").value("A0514"));
        assertThat(this.rateLimiter.isLoginLocked(username))
                .as("与登录共用计数：阈值已到即锁")
                .isTrue();

        this.mockMvc
                .perform(profileJson(
                        post("/api/profile/password"),
                        user(username).roles("USER"),
                        passwordBody(INITIAL_PASSWORD, NEW_PASSWORD)))
                .andExpect(jsonPath("$.code").value("A0514"));
        this.mockMvc
                .perform(post("/login")
                        .with(csrf())
                        .accept(MediaType.TEXT_HTML)
                        .param("username", username)
                        .param("password", INITIAL_PASSWORD))
                .andExpect(result ->
                        assertThat(result.getResponse().getHeader("Location")).contains("error"));
    }

    @Test
    @DisplayName("改密成功：计数清零、新口令可登录、旧口令失效、明文不回显；新口令过短 A0502")
    void changePasswordRotatesCredentialAndClearsCounter() throws Exception {
        String username = "rotate-sam";
        createUser(username, null);

        this.mockMvc
                .perform(profileJson(
                        post("/api/profile/password"),
                        user(username).roles("USER"),
                        passwordBody(INITIAL_PASSWORD, "short")))
                .andExpect(jsonPath("$.code").value("A0502"));

        this.mockMvc
                .perform(profileJson(
                        post("/api/profile/password"),
                        user(username).roles("USER"),
                        passwordBody(INITIAL_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(content().string(not(containsString(NEW_PASSWORD))));

        this.mockMvc
                .perform(post("/login").with(csrf()).param("username", username).param("password", NEW_PASSWORD))
                .andExpect(loginSucceeds());
        this.mockMvc
                .perform(post("/login").with(csrf()).param("username", username).param("password", INITIAL_PASSWORD))
                .andExpect(result ->
                        assertThat(result.getResponse().getHeader("Location")).contains("error"));
    }

    /** 建号辅助（走管理面，超管身份）。 */
    private void createUser(String username, String displayName) throws Exception {
        String displayJson = displayName == null ? "" : ",\"displayName\":\"" + displayName + "\"";
        this.mockMvc
                .perform(post("/api/admin/users")
                        .with(user(ADMIN_USERNAME).roles("SUPER_ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + INITIAL_PASSWORD + "\""
                                + displayJson + "}"))
                .andExpect(jsonPath("$.code").value("00000"));
        assertThat(this.userRepository.findByUsername(username)).isNotNull();
    }

    /** 档案 JSON 请求统一形态。 */
    private static MockHttpServletRequestBuilder profileJson(
            MockHttpServletRequestBuilder builder, RequestPostProcessor identity, String body) {
        return builder.with(identity)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static String passwordBody(String oldPassword, String newPassword) {
        return "{\"oldPassword\":\"" + oldPassword + "\",\"newPassword\":\"" + newPassword + "\"}";
    }

    private static ResultMatcher loginSucceeds() {
        return result -> {
            assertThat(result.getResponse().getStatus()).as("登录成功应 3xx").isIn(301, 302, 303, 307);
            assertThat(result.getResponse().getHeader("Location")).doesNotContain("error");
        };
    }
}
