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
import io.github.oatelauser.jauth.core.token.TokenHash;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.util.UuidV7;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 内省/撤销端到端集成测试（B6-fix 缺陷 1/4 的双模式证明）：真实走过框架
 * OAuth2TokenIntrospectionAuthenticationProvider 与 OAuth2TokenRevocationAuthenticationProvider——
 * 授权码（PKCE）换 opaque 令牌 → /introspect 返回 active + sub/username 富化 → /revoke 撤销 → 内省转
 * inactive。jdbc 模式额外断言库列仍存哈希（明文回填仅存在于返回对象）。
 *
 * @author oatelauser
 */
class TokenIntrospectionRevocationIntegrationTest {

    /** 占位凭证：测试专用，非真实形状。 */
    private static final String USERNAME = "alice";

    private static final String PASSWORD_HASH_PLACEHOLDER = "placeholder-hash-not-real";

    private static final String REDIRECT_URI = "https://example.com/cb";

    /** PKCE code_verifier（占位随机形状，RFC 7636 允许 43-128 字符 Base64URL）。 */
    private static final String CODE_VERIFIER = "placeholder-code-verifier-43-chars-minimum-abcdef";

    abstract static class FlowSupport {

        @Autowired
        protected MockMvc mockMvc;

        @Autowired
        protected UserRepository userRepository;

        protected abstract String clientId();

        protected abstract String clientSecret();

        @Test
        @DisplayName("授权码流 → 内省 active+富化 → 撤销 → 内省 inactive（框架 provider 全程真实走通）")
        void introspectActiveEnrichedThenRevoke() throws Exception {
            String userId = ensureAliceSeeded();

            String accessToken = authorizationCodeFlowToOpaqueToken();
            assertIntrospectionActiveAndEnriched(accessToken, userId);
            assertRevocationMakesTokenInactive(accessToken);
        }

        /** 幂等播种测试用户（同 context 多测试方法不撞唯一索引）。 */
        protected String ensureAliceSeeded() {
            JauthUser existing = this.userRepository.findByUsername(USERNAME);
            if (existing != null) {
                return existing.id();
            }
            JauthUser alice = new JauthUser(
                    UuidV7.generate().toString(),
                    USERNAME,
                    PASSWORD_HASH_PLACEHOLDER,
                    null,
                    null,
                    JauthUser.ROLE_USER,
                    JauthUser.STATUS_ACTIVE,
                    null,
                    Instant.now());
            this.userRepository.save(alice);
            return alice.id();
        }

        protected String authorizationCodeFlowToOpaqueToken() throws Exception {
            // 授权端点读 query string（框架 getQueryParameters 只认 getQueryString() 里的键），须编码进 URI
            java.net.URI authorizeUri = org.springframework.web.util.UriComponentsBuilder.fromPath("/oauth2/authorize")
                    .queryParam("response_type", "code")
                    .queryParam("client_id", clientId())
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
            assertThat(code).as("授权码已随 302 下发").isNotNull();

            String tokenResponse = this.mockMvc
                    .perform(post("/oauth2/token")
                            .with(httpBasic(clientId(), clientSecret()))
                            .with(csrf())
                            .param("grant_type", "authorization_code")
                            .param("code", code)
                            .param("redirect_uri", REDIRECT_URI)
                            .param("code_verifier", CODE_VERIFIER))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.token_type").value("Bearer"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString(StandardCharsets.UTF_8);
            return JsonPath.read(tokenResponse, "$.access_token");
        }

        protected void assertIntrospectionActiveAndEnriched(String accessToken, String userId) throws Exception {
            this.mockMvc
                    .perform(post("/oauth2/introspect")
                            .with(httpBasic(clientId(), clientSecret()))
                            .with(csrf())
                            .param("token", accessToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(true))
                    .andExpect(jsonPath("$.token_type").value("Bearer"))
                    .andExpect(jsonPath("$.sub").value(userId))
                    .andExpect(jsonPath("$.username").value(USERNAME));
        }

        private void assertRevocationMakesTokenInactive(String accessToken) throws Exception {
            this.mockMvc
                    .perform(post("/oauth2/revoke")
                            .with(httpBasic(clientId(), clientSecret()))
                            .with(csrf())
                            .param("token", accessToken))
                    .andExpect(status().isOk());

            this.mockMvc
                    .perform(post("/oauth2/introspect")
                            .with(httpBasic(clientId(), clientSecret()))
                            .with(csrf())
                            .param("token", accessToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(false));
        }

        /**
         * mock 用户须携带 FactorGrantedAuthority（Security 7 的 auth_time 模型）：真实表单登录经
         * DaoAuthenticationProvider 会附加 FACTOR_PASSWORD，JwtGenerator 生成 OIDC id_token 时强依赖；
         * 裸 user() 无因子授权会在 openid scope 下抛 authenticationTime cannot be null。
         */
        private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                        .UserRequestPostProcessor
                authenticatedAlice() {
            return user(USERNAME)
                    .authorities(
                            org.springframework.security.core.authority.FactorGrantedAuthority.fromAuthority(
                                    org.springframework.security.core.authority.FactorGrantedAuthority
                                            .PASSWORD_AUTHORITY),
                            new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"));
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

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, classes = JauthHubStarterTestApplication.class)
    @AutoConfigureMockMvc
    @TestPropertySource(
            properties = {
                "spring.flyway.enabled=false",
                "spring.autoconfigure.exclude=io.github.oatelauser.springplus.web.autoconfigure.ExceptionHandlingAutoConfiguration,"
                        + "io.github.oatelauser.springplus.web.autoconfigure.SpringPlusWebAutoConfiguration",
                "jauth-hub.clients[0].client-id=demo-client",
                "jauth-hub.clients[0].client-name=Demo Client",
                "jauth-hub.clients[0].client-secret=demo-secret",
                "jauth-hub.clients[0].grant-types[0]=authorization_code",
                "jauth-hub.clients[0].grant-types[1]=refresh_token",
                "jauth-hub.clients[0].redirect-uris[0]=https://example.com/cb",
                "jauth-hub.clients[0].scopes[0]=openid"
            })
    class MemoryMode extends FlowSupport {

        @Override
        protected String clientId() {
            return "demo-client";
        }

        @Override
        protected String clientSecret() {
            return "demo-secret";
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, classes = JauthHubStarterTestApplication.class)
    @AutoConfigureMockMvc
    @TestPropertySource(
            properties = {
                "spring.autoconfigure.exclude=io.github.oatelauser.springplus.web.autoconfigure.ExceptionHandlingAutoConfiguration,"
                        + "io.github.oatelauser.springplus.web.autoconfigure.SpringPlusWebAutoConfiguration",
                "spring.datasource.url=jdbc:h2:mem:jauth-introspect-jdbc;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "jauth-hub.storage=jdbc",
                "jauth-hub.clients[0].client-id=jdbc-client",
                "jauth-hub.clients[0].client-name=JDBC Client",
                "jauth-hub.clients[0].client-secret=jdbc-secret",
                "jauth-hub.clients[0].grant-types[0]=authorization_code",
                "jauth-hub.clients[0].grant-types[1]=refresh_token",
                "jauth-hub.clients[0].redirect-uris[0]=https://example.com/cb",
                "jauth-hub.clients[0].scopes[0]=openid"
            })
    class JdbcMode extends FlowSupport {

        @Autowired
        private DataSource dataSource;

        @Override
        protected String clientId() {
            return "jdbc-client";
        }

        @Override
        protected String clientSecret() {
            return "jdbc-secret";
        }

        @Test
        @DisplayName("DB 列仍存哈希：明文回填只存在于返回对象，不落库（缺陷 1 语义边界）")
        void databaseColumnStaysHashedAfterBackfill() throws Exception {
            String userId = ensureAliceSeeded();
            String accessToken = authorizationCodeFlowToOpaqueToken();

            // 先走一次内省（触发明文回填路径），再断言库列是哈希
            assertIntrospectionActiveAndEnriched(accessToken, userId);

            Integer hashedRows = new JdbcTemplate(this.dataSource)
                    .queryForObject(
                            "SELECT COUNT(*) FROM oauth2_authorization WHERE access_token_value = ?",
                            Integer.class,
                            TokenHash.sha256Hex(accessToken));
            Integer plaintextRows = new JdbcTemplate(this.dataSource)
                    .queryForObject(
                            "SELECT COUNT(*) FROM oauth2_authorization WHERE access_token_value = ?",
                            Integer.class,
                            accessToken);
            assertThat(hashedRows)
                    .as("回填明文不落库：access_token_value 列存的是 SHA-256 哈希")
                    .isEqualTo(1);
            assertThat(plaintextRows).as("库中无明文令牌行").isZero();
        }
    }
}
