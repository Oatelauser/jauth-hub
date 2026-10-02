package io.github.oatelauser.jauth.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.oatelauser.jauth.app.JauthHubAppApplication;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * sudo 门端到端集成测试（v1.2 C3）：passkey+sudo 双开的自有 H2 档——角色变更在未强认证时被拦
 * （A0515，页面 JS 的跳转分支按此码触发）；{@code strong_auth_at} 打点后（TTL 内）放行。
 * 建号端点不在敏感面（决议：改密×2 + 角色变更，v1.3 D1 用户拍板追加固态开关），本测试顺带回归该边界。
 *
 * @author oatelauser
 */
@SpringBootTest(classes = JauthHubAppApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:jauth-sudo-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "jauth-hub.bootstrap.superadmin.username=superadmin",
            "jauth-hub.bootstrap.superadmin.password=super-secret-placeholder",
            "jauth-hub.passkey.enabled=true",
            "jauth-hub.sudo.enabled=true"
        })
class SudoGatingIntegrationTest {

    private static final String ADMIN_USERNAME = "superadmin";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("角色变更：未强认证 → A0515；打点后（TTL 内）放行；建号端点不受门限制")
    void roleChangeGatedUntilStrongAuthStamped() throws Exception {
        // 建号不在敏感面：无强认证也可完成（边界回归）
        String targetId = createTargetUser("sudo-target");

        // 未打点（bootstrap 超管 strong_auth_at = NULL）：拦 A0515
        this.mockMvc
                .perform(post("/api/admin/users/{id}/role", targetId)
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(jsonPath("$.code").value("A0515"));

        // passkey 成功路径的等价打点（桥逻辑另有单测）：TTL 内放行
        this.userRepository.updateStrongAuthAt(adminId(), Instant.now());
        this.mockMvc
                .perform(post("/api/admin/users/{id}/role", targetId)
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));
        assertThat(this.userRepository.findById(targetId).role()).isEqualTo(JauthUser.ROLE_SUPERADMIN);
    }

    @Test
    @DisplayName("停用/启用：未强认证 → A0515；打点后放行（v1.3 D1 用户拍板纳入敏感面）")
    void statusToggleGatedUntilStrongAuthStamped() throws Exception {
        // 同类前序方法可能已给超管打点（共享上下文）：先打一张远过期的时间戳钉死"未强认证"起点
        this.userRepository.updateStrongAuthAt(adminId(), Instant.now().minusSeconds(86_400));
        String targetId = createTargetUser("sudo-status-target");

        // 未打点：拦 A0515
        this.mockMvc
                .perform(post("/api/admin/users/{id}/status", targetId)
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(jsonPath("$.code").value("A0515"));

        // 打点后（TTL 内）放行，且停用语义生效
        this.userRepository.updateStrongAuthAt(adminId(), Instant.now());
        this.mockMvc
                .perform(post("/api/admin/users/{id}/status", targetId)
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.status").value(JauthUser.STATUS_DISABLED));
    }

    private String createTargetUser(String username) throws Exception {
        this.mockMvc
                .perform(post("/api/admin/users")
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"target-pass-placeholder\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));
        return this.userRepository.findByUsername(username).id();
    }

    private String adminId() {
        return this.userRepository.findByUsername(ADMIN_USERNAME).id();
    }

    private static RequestPostProcessor admin() {
        return user(ADMIN_USERNAME).roles("SUPER_ADMIN");
    }
}
