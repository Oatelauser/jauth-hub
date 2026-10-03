package io.github.oatelauser.jauth.selfservice.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.oatelauser.jauth.core.org.Installation;
import io.github.oatelauser.jauth.core.org.InstallationService;
import io.github.oatelauser.jauth.core.org.Org;
import io.github.oatelauser.jauth.core.org.OrgMember;
import io.github.oatelauser.jauth.core.org.OrgRepository;
import io.github.oatelauser.jauth.core.org.OrgRole;
import io.github.oatelauser.jauth.core.org.OrgService;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.util.UuidV7;
import io.github.oatelauser.jauth.selfservice.web.OwnedAppService;
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
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * B11 旗舰 E2E（照 starter 的 CeilingEnforcementIntegrationTest，H2 jdbc 模式 + starter 真实协议链 +
 * selfservice 装配全在场）：registerOrg 服务面注册 org 应用 → 成员 request 安装 → OWNER approve 收窄
 * ceiling → 成员走 consent（超集提交）→ 换 token → 发行 scope = 请求 ∩ consent ∩ ceiling。B9 取交防线在
 * B11 的"页面/服务面全程自产数据"路径上闭环验收。
 *
 * @author oatelauser
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        classes = OrgAppInstallationFlowIntegrationTest.E2eTestApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:jauth-b11-e2e;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "jauth-hub.storage=jdbc"
        })
class OrgAppInstallationFlowIntegrationTest {

    private static final String REDIRECT_URI = "https://b11.example.com/cb";

    private static final String CODE_VERIFIER = "placeholder-code-verifier-43-chars-minimum-abcdef";

    /** 测试占位口令（非真实凭据）。 */
    private static final String PLACEHOLDER_CREDENTIAL = "placeholder-credential-not-real";

    private static final String OWNER_USERNAME = "b11-owner";

    private static final String MEMBER_USERNAME = "b11-member";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private OrgService orgService;

    @Autowired
    private OrgRepository orgRepository;

    @Autowired
    private InstallationService installationService;

    @Autowired
    private OwnedAppService ownedAppService;

    @Test
    @DisplayName("org 应用全链:registerOrg → 成员 request → OWNER approve 收窄 → consent 超集 → token scope=交集")
    void orgAppFromRegistrationToNarrowedTokenScope() throws Exception {
        // -- 0. 域数据：OWNER 建 org、成员 A 加入、registerOrg 注册 org 应用（B11 服务面）、安装审批收窄 ceiling
        JauthUser owner = ensureUser(OWNER_USERNAME);
        JauthUser member = ensureUser(MEMBER_USERNAME);
        Org org = this.orgService.create("b11-acme", owner.id());
        this.orgRepository.saveMember(new OrgMember(org.id(), member.id(), OrgRole.MEMBER, Instant.now()));
        OwnedAppService.Registration registration =
                this.ownedAppService.registerOrg(org.id(), "B11 组织门户", Set.of(REDIRECT_URI), false);
        assertThat(registration.app().clientId()).startsWith("app_");
        Installation installation = this.installationService.request(
                registration.app().id(), org.id(), Set.of("openid", "profile"), member.id());
        this.installationService.approve(installation.id(), owner.id(), Set.of("openid"));

        // -- 1. 成员 A 登录 → authorize → consent 页（org 上下文徽标）
        MockHttpSession session = login(MEMBER_USERNAME);
        MvcResult authorizeFirst = this.mockMvc
                .perform(get(authorizeUri(registration.app().clientId())).session(session))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String consentRedirect = authorizeFirst.getResponse().getHeader("Location");
        assertThat(consentRedirect).contains("/oauth2/consent");
        String consentState = queryParam(consentRedirect, "state");
        // v1.5 B5b：consent 页 302 到 /front/consent（查询串逐字转发）；org 上下文由 SPA 消费
        // /api/consent 状态面（org 三态在 ConsentStateControllerTest 钉死），本链只证 ceiling 剪裁
        this.mockMvc
                .perform(get(consentRedirect).session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(result ->
                        assertThat(result.getResponse().getHeader("Location")).startsWith("/front/consent?"));

        // -- 2. consent 提交超集（openid profile）→ code → token：scope 被剪到 ceiling（= openid）
        String code =
                consentAndGetCode(session, consentState, registration.app().clientId(), "openid", "profile");
        String tokenJson = tokenResponse(Map.of(
                "grant_type", "authorization_code",
                "code", code,
                "redirect_uri", REDIRECT_URI,
                "client_id", registration.app().clientId(),
                "code_verifier", CODE_VERIFIER));
        assertThat((String) JsonPath.read(tokenJson, "$.scope"))
                .as("发行 scope = 请求 ∩ consent ∩ ceiling，ceiling 已收窄到 openid")
                .isEqualTo("openid");
    }

    // ------------------------------------------------------------------ 流程小件（照模板同款）

    private java.net.URI authorizeUri(String clientId) {
        return UriComponentsBuilder.fromPath("/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("scope", "openid profile")
                .queryParam("state", "placeholder-state-not-real")
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("code_challenge", s256(CODE_VERIFIER))
                .queryParam("code_challenge_method", "S256")
                .encode()
                .build()
                .toUri();
    }

    private MockHttpSession login(String username) throws Exception {
        MockHttpSession session = new MockHttpSession();
        this.mockMvc
                .perform(post("/login")
                        .with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", username)
                        .param("password", PLACEHOLDER_CREDENTIAL))
                .andExpect(status().is3xxRedirection());
        return session;
    }

    private String consentAndGetCode(
            MockHttpSession session, String consentState, String clientId, String... submittedScopes) throws Exception {
        // scope 逐项多值提交（真实浏览器每复选框一值；框架 consent 转换器不按空格拆分）
        MvcResult consented = this.mockMvc
                .perform(post("/oauth2/authorize")
                        .session(session)
                        .with(csrf())
                        .param("client_id", clientId)
                        .param("state", consentState)
                        .param("scope", submittedScopes))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String code = queryParam(consented.getResponse().getHeader("Location"), "code");
        assertThat(code).as("consent 提交后 302 下发授权码").isNotNull();
        return code;
    }

    private String tokenResponse(Map<String, String> params) throws Exception {
        MockHttpServletRequestBuilder request = post("/oauth2/token").with(csrf());
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

    private JauthUser ensureUser(String username) {
        JauthUser existing = this.userRepository.findByUsername(username);
        if (existing != null) {
            return existing;
        }
        JauthUser user = new JauthUser(
                UuidV7.generate().toString(),
                username,
                this.passwordEncoder.encode(PLACEHOLDER_CREDENTIAL),
                null,
                null,
                JauthUser.ROLE_USER,
                JauthUser.STATUS_ACTIVE,
                null,
                Instant.now());
        this.userRepository.save(user);
        return user;
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

    /**
     * E2E 宿主壳：组件扫描仅限本包（无组件），装配全来自 starter + selfservice 的 AutoConfiguration.imports——
     * 模拟"宿主引 starter + selfservice"的真实叠加（selfservice 装配排 starter 之后，SPEC §2）。
     */
    @SpringBootApplication
    static class E2eTestApplication {

        /** 宿主契约件：UserDetailsService（真实表单登录，OWNER 与成员两号）。 */
        @Bean
        UserDetailsService e2eUserDetailsService(PasswordEncoder encoder) {
            return new InMemoryUserDetailsManager(
                    User.withUsername(OWNER_USERNAME)
                            .password(encoder.encode(PLACEHOLDER_CREDENTIAL))
                            .roles("USER")
                            .build(),
                    User.withUsername(MEMBER_USERNAME)
                            .password(encoder.encode(PLACEHOLDER_CREDENTIAL))
                            .roles("USER")
                            .build());
        }
    }
}
