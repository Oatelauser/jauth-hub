package io.github.oatelauser.jauth.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.oatelauser.jauth.core.ratelimit.RateLimiter;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.util.UuidV7;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * 限流与登录锁定的协议链集成测试（B7 任务 1；窗口翻页/锁定到期的钟控语义在 core 单测覆盖，此处验装配
 * 与 HTTP 面）：token 端点限额触发 429 + RFC 风格 error=rate_limited + X-RateLimit-* 三头、正常响应带头、
 * 登录连错锁定（口令正确也拒——防爆破语义）。
 *
 * <p>memory 模式足够：限流器与两过滤器不依赖存储；登录走真实表单（测试壳补 UserDetailsService，即
 * 宿主嵌入契约的那一件）。口令字面量为测试占位形状，非真实凭据。
 *
 * @author oatelauser
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, classes = JauthHubStarterTestApplication.class)
@AutoConfigureMockMvc
@Import(RateLimitAndLockoutIntegrationTest.LoginConfig.class)
@TestPropertySource(
        properties = {
            "spring.flyway.enabled=false",
            "spring.autoconfigure.exclude=io.github.oatelauser.springplus.web.autoconfigure.ExceptionHandlingAutoConfiguration,"
                    + "io.github.oatelauser.springplus.web.autoconfigure.SpringPlusWebAutoConfiguration",
            "jauth-hub.rate-limit.limit-per-hour=3",
            "jauth-hub.rate-limit.login-max-failures=3",
            "jauth-hub.clients[0].client-id=limited-client",
            "jauth-hub.clients[0].client-name=Limited Client",
            "jauth-hub.clients[0].client-secret=limited-secret",
            "jauth-hub.clients[0].grant-types[0]=client_credentials",
            "jauth-hub.clients[0].redirect-uris[0]=https://example.com/cb",
            "jauth-hub.clients[0].scopes[0]=openid"
        })
class RateLimitAndLockoutIntegrationTest {

    /** 测试占位口令（非真实凭据）：登录成功路径与失败路径共用同一形状。 */
    private static final String ALICE_CREDENTIAL_PLACEHOLDER = "placeholder-credential-not-real";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("token 端点限额：前 3 次成功响应带三头，第 4 次 429 + error=rate_limited JSON（带头）")
    void tokenEndpointRateLimited() throws Exception {
        for (int i = 0; i < 3; i++) {
            MvcResult result = this.mockMvc
                    .perform(tokenRequest())
                    .andExpect(status().isOk())
                    .andReturn();
            assertThat(result.getResponse().getHeader("X-RateLimit-Limit")).isEqualTo("3");
            assertThat(result.getResponse().getHeader("X-RateLimit-Remaining")).isEqualTo(String.valueOf(2 - i));
            assertThat(result.getResponse().getHeader("X-RateLimit-Reset")).isNotBlank();
        }
        this.mockMvc
                .perform(tokenRequest())
                .andExpect(status().is(429))
                .andExpect(jsonPath("$.error").value("rate_limited"))
                .andExpect(mvcResult -> {
                    var response = mvcResult.getResponse();
                    assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("3");
                    assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
                    assertThat(response.getHeader("X-RateLimit-Reset")).isNotBlank();
                });
    }

    @Test
    @DisplayName("登录连错锁定：3 次错口令后，正确口令也被拒（浏览器 302 /login?error，机器 401 login_locked）")
    void loginLockoutRejectsEvenCorrectPassword() throws Exception {
        ensureAliceSeeded();
        // 走真实失败路径（失败事件桥 → 限流器计数）才能证明装配接线，不直接拨限流器状态
        for (int i = 0; i < 3; i++) {
            this.mockMvc
                    .perform(post("/login")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("username", "alice")
                            .param("password", "wrong-" + i))
                    .andExpect(status().is3xxRedirection());
        }
        assertThat(this.rateLimiter.isLoginLocked("alice")).as("连错 3 次即锁（可配阈值）").isTrue();

        this.mockMvc
                .perform(post("/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "alice")
                        .param("password", ALICE_CREDENTIAL_PLACEHOLDER)
                        .header("Accept", "application/json"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("login_locked"));

        this.mockMvc
                .perform(post("/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "alice")
                        .param("password", ALICE_CREDENTIAL_PLACEHOLDER)
                        .header("Accept", "text/html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(mvcResult -> assertThat(mvcResult.getResponse().getHeader("Location"))
                        .endsWith("/login?error"));
    }

    private RequestBuilder tokenRequest() {
        // client_credentials 走通（有效客户端认证 + 成功签发）：正常响应也带三头是验收点；
        // 框架 sendError 的错误路径会走 ERROR dispatch（MockMvc 下头丢失、容器下保留），不作为断言面
        return post("/oauth2/token")
                .with(httpBasic("limited-client", "limited-secret"))
                .with(csrf())
                .param("grant_type", "client_credentials");
    }

    private void ensureAliceSeeded() {
        if (this.userRepository.findByUsername("alice") != null) {
            return;
        }
        this.userRepository.save(new JauthUser(
                UuidV7.generate().toString(),
                "alice",
                this.passwordEncoder.encode(ALICE_CREDENTIAL_PLACEHOLDER),
                null,
                null,
                JauthUser.ROLE_USER,
                JauthUser.STATUS_ACTIVE,
                null,
                Instant.now()));
    }

    /** 宿主契约件：UserDetailsService（真实表单登录路径；口令为占位形状，非真实凭据）。 */
    @TestConfiguration
    static class LoginConfig {

        @Bean
        UserDetailsService testUserDetailsService(PasswordEncoder encoder) {
            return new InMemoryUserDetailsManager(User.withUsername("alice")
                    .password(encoder.encode(ALICE_CREDENTIAL_PLACEHOLDER))
                    .roles("USER")
                    .build());
        }
    }
}
