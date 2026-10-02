package io.github.oatelauser.jauth.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.oatelauser.jauth.app.JauthHubAppApplication;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 登录 JSON 桥全链 E2E（v1.4 B2，app 壳真装配）：GET /api/login 取 CSRF → 未认证触 /oauth2/authorize
 * （saved request 入 session）→ POST /api/login（JSON + X-CSRF-Token 头）→ redirectUrl = saved request
 * 优先 → 持 session 走 authorize → consent → code → token（PKCE 公开客户端）→ userinfo 200；
 * <b>id_token 的 auth_time == JSON 登录时刻</b>（Security 7 口径：DaoAuthenticationProvider 附加
 * FACTOR_PASSWORD FactorGrantedAuthority，SAS JwtGenerator 取其 issuedAt 为 auth_time——javap 解析
 * 7.1.1 jar 的 getAuthenticationTime 字节码结论，json 登录与表单登录同路）。
 *
 * @author oatelauser
 */
@SpringBootTest(classes = JauthHubAppApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:jauth-login-api-e2e;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "jauth-hub.bootstrap.superadmin.username=superadmin",
            "jauth-hub.bootstrap.superadmin.password=super-secret-placeholder"
        })
class LoginApiEndToEndIntegrationTest {

    /** application-local.yml 的公开教学客户端（PKCE + consent，redirect 固定）。 */
    private static final String REDIRECT_URI = "http://localhost:8080/demo/callback";

    private static final String CODE_VERIFIER = "placeholder-code-verifier-43-chars-minimum-abcdef";

    /** 占位口令（bootstrap 超管播种用，非真实凭据）。 */
    private static final String SUPERADMIN_PASSWORD = "super-secret-placeholder";

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("JSON 登录全链：saved request 落点 → authorize → consent → code → token → userinfo；auth_time=登录时刻")
    void jsonLoginDrivesFullAuthorizationCodeFlow() throws Exception {
        MockHttpSession session = new MockHttpSession();

        // -- 1. 浏览器形态触链上 authenticated 页（302 /login）：ExceptionTranslationFilter 存 saved request。
        // （非浏览器分支的 401 不存请求，SAS 的 /oauth2/authorize 未认证 401 也在过滤器内部消化——这也是装配
        // 守卫注释"两方式默认落点恒为 /"的现场依据；saved request 的真实产生面是 consent 等认证页）
        this.mockMvc
                .perform(get("/oauth2/consent")
                        .session(session)
                        .accept(MediaType.TEXT_HTML)
                        .queryParam("client_id", "demo-public")
                        .queryParam("state", "placeholder-consent-state")
                        .queryParam("scope", "openid"))
                .andExpect(status().is3xxRedirection());

        // -- 2. GET /api/login 取 CSRF（permitAll 状态面）
        MvcResult state = this.mockMvc
                .perform(get("/api/login").session(session).accept(JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.csrfToken").isNotEmpty())
                .andReturn();
        String csrfToken =
                JsonPath.read(state.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.csrfToken");

        // -- 3. JSON 登录（B1 契约：X-CSRF-Token 头携状态面原值）；redirectUrl = saved request 优先（非 /）
        Instant loginAt = Instant.now();
        this.mockMvc
                .perform(post("/api/login")
                        .session(session)
                        .contentType(JSON)
                        .header("X-CSRF-Token", csrfToken)
                        .content("{\"username\":\"superadmin\",\"password\":\"" + SUPERADMIN_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(
                        jsonPath("$.data.redirectUrl").value(org.hamcrest.Matchers.containsString("/oauth2/consent")));

        // -- 4. 持该 session 走 authorize：consent 页出现（demo-public require-consent）
        MvcResult authorizeFirst = this.mockMvc
                .perform(get(authorizeUri()).session(session))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String consentRedirect = authorizeFirst.getResponse().getHeader("Location");
        assertThat(consentRedirect).contains("/oauth2/consent");

        // -- 5. consent 勾选 openid → 302 code
        String consentState = queryParam(consentRedirect, "state");
        MvcResult consented = this.mockMvc
                .perform(post("/oauth2/authorize")
                        .session(session)
                        .with(csrf())
                        .param("client_id", "demo-public")
                        .param("state", consentState)
                        .param("scope", "openid"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String code = queryParam(consented.getResponse().getHeader("Location"), "code");
        assertThat(code).as("consent 提交后 302 下发授权码").isNotNull();

        // -- 6. token 交换（公开客户端 PKCE）：id_token 在手
        String tokenJson = this.mockMvc
                .perform(post("/oauth2/token")
                        .with(csrf())
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("client_id", "demo-public")
                        .param("code_verifier", CODE_VERIFIER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").exists())
                .andExpect(jsonPath("$.id_token").exists())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String accessToken = JsonPath.read(tokenJson, "$.access_token");

        // -- 7. 令牌活性：进程内 /oauth2/introspect（demo-rs 凭证，application-local.yml 种子）。
        // （userinfo 面在 app 上下文被 rs 装配的 HTTP 内省腿接管——MockMvc 无活服务器不可达；进程内
        // 内省与 userinfo 同源（授权服务本进程解析），对"JSON 登录建立的授权链可用"等强）
        this.mockMvc
                .perform(post("/oauth2/introspect")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .httpBasic("demo-rs", "demo-rs-dev-only-placeholder"))
                        .with(csrf())
                        .param("token", accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        // -- 8. auth_time = JSON 登录时刻（FactorGrantedAuthority.issuedAt，秒级容差）
        Number authTime = JsonPath.read(idTokenPayload(tokenJson), "$.auth_time");
        assertThat(authTime).isNotNull();
        assertThat(authTime.longValue()).isBetween(loginAt.getEpochSecond() - 5, loginAt.getEpochSecond() + 5);
    }

    // ------------------------------------------------------------------ 小件

    /** PKCE 授权请求（demo-public 公开客户端，application-local.yml 种子）。 */
    private static java.net.URI authorizeUri() {
        return UriComponentsBuilder.fromPath("/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", "demo-public")
                .queryParam("scope", "openid profile")
                .queryParam("state", "placeholder-state-not-real")
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("code_challenge", s256(CODE_VERIFIER))
                .queryParam("code_challenge_method", "S256")
                .encode()
                .build()
                .toUri();
    }

    /** id_token 的 payload 段 base64url 解码为 JSON 文本（只读不验签——签发链路真实验证在内省/userinfo 面）。 */
    private static String idTokenPayload(String tokenJson) {
        String idToken = JsonPath.read(tokenJson, "$.id_token");
        String payload = idToken.split("\\.")[1];
        return new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
    }

    private static String queryParam(String location, String name) {
        if (location == null || !location.contains("?")) {
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
