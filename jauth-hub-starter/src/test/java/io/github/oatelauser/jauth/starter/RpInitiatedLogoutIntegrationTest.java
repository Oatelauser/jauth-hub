package io.github.oatelauser.jauth.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.util.UuidV7;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * RP-Initiated Logout 集成测试（B7 任务 5）：带 id_token_hint 与 post_logout_redirect_uri 的登出请求
 * 302 到已注册目标、会话失效；未注册的 post_logout_redirect_uri 不被采纳（框架校验语义白名单精确匹配）。
 *
 * @author oatelauser
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, classes = JauthHubStarterTestApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.autoconfigure.exclude=io.github.oatelauser.springplus.web.autoconfigure.ExceptionHandlingAutoConfiguration,"
                    + "io.github.oatelauser.springplus.web.autoconfigure.SpringPlusWebAutoConfiguration",
            "spring.datasource.url=jdbc:h2:mem:jauth-logout-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "jauth-hub.storage=jdbc",
            "jauth-hub.clients[0].client-id=logout-rp",
            "jauth-hub.clients[0].client-name=Logout RP",
            "jauth-hub.clients[0].client-secret=logout-rp-secret",
            "jauth-hub.clients[0].grant-types[0]=authorization_code",
            "jauth-hub.clients[0].redirect-uris[0]=https://rp.example.com/cb",
            "jauth-hub.clients[0].post-logout-redirect-uris[0]=https://rp.example.com/logged-out",
            "jauth-hub.clients[0].scopes[0]=openid"
        })
class RpInitiatedLogoutIntegrationTest {

    private static final String REDIRECT_URI = "https://rp.example.com/cb";

    private static final String POST_LOGOUT_REDIRECT = "https://rp.example.com/logged-out";

    private static final String CODE_VERIFIER = "placeholder-code-verifier-43-chars-minimum-abcdef";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("id_token_hint + post_logout_redirect_uri：302 到已注册目标 + state 回传 + 会话失效")
    void logoutRedirectsToRegisteredTargetAndInvalidatesSession() throws Exception {
        ensureAliceSeeded();
        String idToken = obtainIdToken();

        MockHttpSession session = new MockHttpSession();
        MvcResult logout = this.mockMvc
                .perform(post("/connect/logout")
                        .with(authenticatedAlice())
                        .with(csrf())
                        .session(session)
                        .param("id_token_hint", idToken)
                        .param("client_id", "logout-rp")
                        .param("post_logout_redirect_uri", POST_LOGOUT_REDIRECT)
                        .param("state", "placeholder-logout-state"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        assertThat(logout.getResponse().getHeader("Location"))
                .isEqualTo(POST_LOGOUT_REDIRECT + "?state=placeholder-logout-state");
        assertThat(session.isInvalid()).as("登出成功即失效会话").isTrue();
    }

    @Test
    @DisplayName("未注册的 post_logout_redirect_uri：登出仍完成但不重定向到任意外站（白名单语义）")
    void unregisteredPostLogoutRedirectIsNotHonored() throws Exception {
        ensureAliceSeeded();
        String idToken = obtainIdToken();

        MvcResult logout = this.mockMvc
                .perform(post("/connect/logout")
                        .with(authenticatedAlice())
                        .with(csrf())
                        .param("id_token_hint", idToken)
                        .param("post_logout_redirect_uri", "https://attacker.example.com/steal"))
                .andExpect(status().is4xxClientError())
                .andReturn();
        assertThat(String.valueOf(logout.getResponse().getHeader("Location"))).doesNotContain("attacker.example.com");
    }

    /** 授权码流取 id_token（openid scope；consent 客户端未开确认页直发 code）。 */
    private String obtainIdToken() throws Exception {
        java.net.URI authorizeUri = UriComponentsBuilder.fromPath("/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", "logout-rp")
                .queryParam("scope", "openid")
                .queryParam("state", "placeholder-state-not-real")
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("code_challenge", s256(CODE_VERIFIER))
                .queryParam("code_challenge_method", "S256")
                .encode()
                .build()
                .toUri();
        MvcResult authorizeResult = this.mockMvc
                .perform(get(authorizeUri).with(authenticatedAlice()))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String code = queryParam(authorizeResult.getResponse().getHeader("Location"), "code");
        assertThat(code).isNotNull();

        String tokenResponse = this.mockMvc
                .perform(post("/oauth2/token")
                        .with(httpBasic("logout-rp", "logout-rp-secret"))
                        .with(csrf())
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("code_verifier", CODE_VERIFIER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id_token").exists())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(tokenResponse, "$.id_token");
    }

    private void ensureAliceSeeded() {
        if (this.userRepository.findByUsername("alice") != null) {
            return;
        }
        this.userRepository.save(new JauthUser(
                UuidV7.generate().toString(),
                "alice",
                "placeholder-hash-not-real",
                null,
                null,
                JauthUser.ROLE_USER,
                JauthUser.STATUS_ACTIVE,
                null,
                Instant.now()));
    }

    private static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor authenticatedAlice() {
        return user("alice")
                .authorities(
                        FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY),
                        new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static String queryParam(String location, String name) {
        if (location == null) {
            return null;
        }
        return java.util.Arrays.stream(
                        location.substring(location.indexOf('?') + 1).split("&"))
                .map(param -> param.split("=", 2))
                .filter(pair -> pair[0].equals(name))
                .map(pair -> java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8))
                .findFirst()
                .orElse(null);
    }

    private static String s256(String codeVerifier) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable on this JVM", ex);
        }
    }
}
