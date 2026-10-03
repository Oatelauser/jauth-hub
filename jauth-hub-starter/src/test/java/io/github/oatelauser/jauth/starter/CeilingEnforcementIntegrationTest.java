package io.github.oatelauser.jauth.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.oatelauser.jauth.core.client.ClientOwner;
import io.github.oatelauser.jauth.core.client.JauthJdbcRegisteredClientRepository;
import io.github.oatelauser.jauth.core.org.Installation;
import io.github.oatelauser.jauth.core.org.InstallationService;
import io.github.oatelauser.jauth.core.org.Org;
import io.github.oatelauser.jauth.core.org.OrgService;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.util.UuidV7;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * ceiling 取交端到端集成测试（B9，starter 真实链，H2 jdbc 模式）：组织客户端（owner 两列 + APPROVED 安装）→
 * consent 提交超集 → 授权码在授权服务链内被剪到请求∩ceiling → access token scope = 交集；id_token 与 opaque
 * 内省两口径的 orgs claim 同形；安装 ceiling 事后收缩（revoke → 重发 → 小 ceiling 再批）→ 存量 consent 的
 * 静默再授权同样被剪（防绕过）。个人客户端回归由既有 EndToEndFlowIntegrationTest 覆盖。
 *
 * @author oatelauser
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, classes = JauthHubStarterTestApplication.class)
@AutoConfigureMockMvc
@Import(CeilingEnforcementIntegrationTest.CeilingConfig.class)
@TestPropertySource(
        properties = {
            "spring.autoconfigure.exclude=io.github.oatelauser.springplus.web.autoconfigure.ExceptionHandlingAutoConfiguration,"
                    + "io.github.oatelauser.springplus.web.autoconfigure.SpringPlusWebAutoConfiguration",
            "spring.datasource.url=jdbc:h2:mem:jauth-ceiling;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "jauth-hub.storage=jdbc",
            "jauth-hub.clients[0].client-id=org-app",
            "jauth-hub.clients[0].client-name=Org App",
            "jauth-hub.clients[0].grant-types[0]=authorization_code",
            "jauth-hub.clients[0].redirect-uris[0]=https://example.com/cb",
            "jauth-hub.clients[0].require-authorization-consent=true",
            "jauth-hub.clients[0].scopes[0]=openid",
            "jauth-hub.clients[0].scopes[1]=profile",
            "jauth-hub.clients[1].client-id=ceiling-rs",
            "jauth-hub.clients[1].client-name=Ceiling Introspector",
            "jauth-hub.clients[1].client-secret=ceiling-rs-secret",
            "jauth-hub.clients[1].grant-types[0]=client_credentials",
            "jauth-hub.clients[1].redirect-uris[0]=https://example.com/unused",
            "jauth-hub.clients[1].scopes[0]=openid"
        })
class CeilingEnforcementIntegrationTest {

    private static final String REDIRECT_URI = "https://example.com/cb";

    private static final String CODE_VERIFIER = "placeholder-code-verifier-43-chars-minimum-abcdef";

    /** 测试占位口令（非真实凭据）。 */
    private static final String ALICE_CREDENTIAL_PLACEHOLDER = "placeholder-credential-not-real";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Autowired
    private JauthJdbcRegisteredClientRepository jdbcClientRepository;

    @Autowired
    private OrgService orgService;

    @Autowired
    private InstallationService installationService;

    @Test
    @DisplayName("组织客户端全链:consent 超集 → 令牌 scope=请求∩ceiling;orgs claim 三口径;ceiling 收缩后静默再授权仍被剪")
    void orgClientCeilingIntersectionEndToEnd() throws Exception {
        // -- 0. 域数据:alice(用户+表单登录) + org(OWNER) + 组织客户端 + APPROVED 安装(首轮 ceiling=全部请求)
        JauthUser alice = ensureAliceSeeded();
        Org org = this.orgService.create("acme-ceiling", alice.id());
        RegisteredClient orgApp = this.registeredClientRepository.findByClientId("org-app");
        this.jdbcClientRepository.save(orgApp, ClientOwner.ofOrg(org.id()));
        Installation installation =
                this.installationService.request(orgApp.getId(), org.id(), Set.of("openid", "profile"), alice.id());
        this.installationService.approve(installation.id(), alice.id(), Set.of("openid", "profile"));

        // -- 1. 登录 → authorize → consent 页:隐式徽标(org 上下文)+ 全 scope 可勾
        MockHttpSession session = loginAlice();
        MvcResult authorizeFirst = this.mockMvc
                .perform(get(authorizeUri()).session(session))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String consentRedirect = authorizeFirst.getResponse().getHeader("Location");
        assertThat(consentRedirect).contains("/oauth2/consent");
        String consentState = queryParam(consentRedirect, "state");
        // v1.5 B5b：consent 页 302 到 /front/consent（查询串逐字转发）；org 徽标/ceiling 勾选面
        // 由 SPA 消费 /api/consent 状态（org 三态在 ConsentStateControllerTest 钉死），本链只证剪裁
        this.mockMvc
                .perform(get(consentRedirect).session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(result ->
                        assertThat(result.getResponse().getHeader("Location")).startsWith("/front/consent?"));

        // -- 2. consent 提交超集(openid profile,即使手造 POST 全勾)→ code → token:scope 被剪到 ceiling
        //    scope 逐项多值提交(真实浏览器每复选框一值;框架 consent 转换器不按空格拆分)
        String code = consentAndGetCode(session, consentState, "openid", "profile");
        String tokenJson = tokenResponse(
                null,
                Map.of(
                        "grant_type", "authorization_code",
                        "code", code,
                        "redirect_uri", REDIRECT_URI,
                        "client_id", "org-app",
                        "code_verifier", CODE_VERIFIER));
        assertThat((String) JsonPath.read(tokenJson, "$.scope")).isEqualTo("openid profile");

        // -- 3. orgs claim:id_token(JWT payload)与 opaque 内省两口径同形(id/name/role)
        String idToken = JsonPath.read(tokenJson, "$.id_token");
        String payload = new String(Base64.getUrlDecoder().decode(idToken.split("\\.")[1]), StandardCharsets.UTF_8);
        assertThat((String) JsonPath.read(payload, "$.orgs[0].id")).isEqualTo(org.id());
        assertThat((String) JsonPath.read(payload, "$.orgs[0].name")).isEqualTo("acme-ceiling");
        assertThat((String) JsonPath.read(payload, "$.orgs[0].role")).isEqualTo("OWNER");
        String accessToken = JsonPath.read(tokenJson, "$.access_token");
        this.mockMvc
                .perform(post("/oauth2/introspect")
                        .with(httpBasic("ceiling-rs", "ceiling-rs-secret"))
                        .with(csrf())
                        .param("token", accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.scope").value("openid profile"))
                .andExpect(jsonPath("$.orgs[0].id").value(org.id()))
                .andExpect(jsonPath("$.orgs[0].name").value("acme-ceiling"))
                .andExpect(jsonPath("$.orgs[0].role").value("OWNER"));

        // -- 4. ceiling 事后收缩(revoke → 重发 → 小 ceiling 再批):存量 consent 的静默再授权同样被剪
        this.installationService.revoke(installation.id(), alice.id());
        Installation reRequested =
                this.installationService.request(orgApp.getId(), org.id(), Set.of("openid", "profile"), alice.id());
        this.installationService.approve(reRequested.id(), alice.id(), Set.of("openid"));

        MvcResult silentAuthorize = this.mockMvc
                .perform(get(authorizeUri()).session(session))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        assertThat(silentAuthorize.getResponse().getHeader("Location"))
                .as("存量 consent 覆盖请求 scope,静默直达授权码")
                .contains("code=");
        String silentCode = queryParam(silentAuthorize.getResponse().getHeader("Location"), "code");
        String silentJson = tokenResponse(
                null,
                Map.of(
                        "grant_type", "authorization_code",
                        "code", silentCode,
                        "redirect_uri", REDIRECT_URI,
                        "client_id", "org-app",
                        "code_verifier", CODE_VERIFIER));
        assertThat((String) JsonPath.read(silentJson, "$.scope"))
                .as("ceiling 收缩后,静默再授权的发行 scope 也被剪到交集")
                .isEqualTo("openid");
    }

    // ------------------------------------------------------------------ 流程小件

    private java.net.URI authorizeUri() {
        return UriComponentsBuilder.fromPath("/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", "org-app")
                .queryParam("scope", "openid profile")
                .queryParam("state", "placeholder-state-not-real")
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("code_challenge", s256(CODE_VERIFIER))
                .queryParam("code_challenge_method", "S256")
                .encode()
                .build()
                .toUri();
    }

    private MockHttpSession loginAlice() throws Exception {
        MockHttpSession session = new MockHttpSession();
        this.mockMvc
                .perform(post("/login")
                        .with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "alice")
                        .param("password", ALICE_CREDENTIAL_PLACEHOLDER))
                .andExpect(status().is3xxRedirection());
        return session;
    }

    private String consentAndGetCode(MockHttpSession session, String consentState, String... submittedScopes)
            throws Exception {
        MvcResult consented = this.mockMvc
                .perform(post("/oauth2/authorize")
                        .session(session)
                        .with(csrf())
                        .param("client_id", "org-app")
                        .param("state", consentState)
                        .param("scope", submittedScopes))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String code = queryParam(consented.getResponse().getHeader("Location"), "code");
        assertThat(code).as("consent 提交后 302 下发授权码").isNotNull();
        return code;
    }

    private String tokenResponse(String basic, Map<String, String> params) throws Exception {
        MockHttpServletRequestBuilder request = post("/oauth2/token").with(csrf());
        if (basic != null) {
            String[] credentials = basic.split(":");
            request = request.with(httpBasic(credentials[0], credentials[1]));
        }
        for (Map.Entry<String, String> entry : params.entrySet()) {
            request = request.param(entry.getKey(), entry.getValue());
        }
        return this.mockMvc
                .perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private JauthUser ensureAliceSeeded() {
        JauthUser existing = this.userRepository.findByUsername("alice");
        if (existing != null) {
            return existing;
        }
        JauthUser alice = new JauthUser(
                UuidV7.generate().toString(),
                "alice",
                this.passwordEncoder.encode(ALICE_CREDENTIAL_PLACEHOLDER),
                null,
                null,
                JauthUser.ROLE_USER,
                JauthUser.STATUS_ACTIVE,
                null,
                Instant.now());
        this.userRepository.save(alice);
        return alice;
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

    /** 宿主契约件:UserDetailsService(真实表单登录)。 */
    @TestConfiguration
    static class CeilingConfig {

        @Bean
        UserDetailsService ceilingUserDetailsService(PasswordEncoder encoder) {
            return new InMemoryUserDetailsManager(User.withUsername("alice")
                    .password(encoder.encode(ALICE_CREDENTIAL_PLACEHOLDER))
                    .roles("USER")
                    .build());
        }
    }
}
