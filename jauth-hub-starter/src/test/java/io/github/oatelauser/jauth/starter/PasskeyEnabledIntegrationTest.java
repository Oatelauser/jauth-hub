package io.github.oatelauser.jauth.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.oatelauser.jauth.core.passkey.InMemoryPasskeyCredentialRepository;
import io.github.oatelauser.jauth.core.passkey.JauthUserEntityRepository;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * passkey 开启 + memory 存储集成测试（SPEC §5 v1.2）：端点认领进协议链、jauth 双仓储适配在场、
 * CSRF 下挑战端点可用。RP 参数走默认推导（issuer http://localhost:9000 → rpId localhost）。
 *
 * <p>UserDetailsService 由测试宿主供给——WebAuthnConfigurer 构链硬性要求（真实宿主 = AppUserDetailsService；
 * 裸宿主由 Boot 默认用户兜底），此处显式给确定性实现。
 *
 * @author oatelauser
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        classes = {JauthHubStarterTestApplication.class, PasskeyEnabledIntegrationTest.PasskeyHostConfiguration.class})
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.flyway.enabled=false",
            "spring.autoconfigure.exclude=io.github.oatelauser.springplus.web.autoconfigure.ExceptionHandlingAutoConfiguration,"
                    + "io.github.oatelauser.springplus.web.autoconfigure.SpringPlusWebAutoConfiguration",
            "jauth-hub.passkey.enabled=true",
            "jauth-hub.clients[0].client-id=demo-client",
            "jauth-hub.clients[0].client-name=Demo Client",
            "jauth-hub.clients[0].client-secret=demo-secret",
            "jauth-hub.clients[0].grant-types[0]=authorization_code",
            "jauth-hub.clients[0].grant-types[1]=refresh_token",
            "jauth-hub.clients[0].redirect-uris[0]=https://example.com/cb",
            "jauth-hub.clients[0].scopes[0]=openid"
        })
class PasskeyEnabledIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private List<SecurityFilterChain> filterChains;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("端点认领：/webauthn/** 与 /login/webauthn 进协议链（/login 精确匹配不含子路径，须单独认领）")
    void chainClaimsWebauthnEndpoints() {
        SecurityFilterChain chain = this.filterChains.get(0);
        assertThat(chain.matches(request("POST", "/webauthn/register/options"))).isTrue();
        assertThat(chain.matches(request("POST", "/webauthn/register"))).isTrue();
        assertThat(chain.matches(request("POST", "/login/webauthn"))).isTrue();
        assertThat(chain.matches(request("POST", "/webauthn/authenticate/options")))
                .isTrue();
    }

    @Test
    @DisplayName("jauth 双仓储在场：用户句柄适配 + memory 凭据仓储（WebAuthnConfigurer 按 bean 类型发现）")
    void jauthRepositoriesArePickedUp() {
        assertThat(this.applicationContext.getBean(PublicKeyCredentialUserEntityRepository.class))
                .isInstanceOf(JauthUserEntityRepository.class);
        assertThat(this.applicationContext.getBean(UserCredentialRepository.class))
                .isInstanceOf(InMemoryPasskeyCredentialRepository.class);
    }

    @Test
    @DisplayName("挑战端点可用：CSRF 下 POST /webauthn/authenticate/options 返回含 challenge/rpId 的 JSON")
    void authenticateOptionsIssuesChallengeJson() throws Exception {
        this.mockMvc
                .perform(post("/webauthn/authenticate/options").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challenge").exists())
                .andExpect(jsonPath("$.rpId").value("localhost"));
    }

    @Test
    @DisplayName("用户句柄视图接通：findByUsername 命中 jauth_user（handle = id 的 UTF-8 Bytes）")
    void userEntityRepositoryAdaptsJauthUsers() {
        this.userRepository.save(new JauthUser(
                "018f0000-0000-7000-8000-0000000000aa",
                "alice",
                "{bcrypt}placeholder-not-a-real-hash",
                null,
                null,
                JauthUser.ROLE_USER,
                JauthUser.STATUS_ACTIVE,
                null,
                Instant.parse("2026-10-01T07:00:00Z")));
        PublicKeyCredentialUserEntityRepository userEntities =
                this.applicationContext.getBean(PublicKeyCredentialUserEntityRepository.class);

        assertThat(userEntities.findByUsername("alice")).isNotNull();
    }

    private static MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    /** passkey 宿主形态：WebAuthn 构链要求的 UserDetailsService（接 jauth 用户面，角色来自 JauthUser.role）。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class PasskeyHostConfiguration {

        @Bean
        UserDetailsService passkeyUserDetailsService(UserRepository userRepository) {
            return username -> {
                JauthUser user = userRepository.findByUsername(username);
                return User.withUsername(username)
                        .password(user.passwordHash())
                        .roles(user.role())
                        .build();
            };
        }
    }
}
