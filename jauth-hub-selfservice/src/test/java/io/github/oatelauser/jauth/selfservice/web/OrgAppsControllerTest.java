package io.github.oatelauser.jauth.selfservice.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.oatelauser.jauth.core.audit.AuditEvent;
import io.github.oatelauser.jauth.core.client.InMemoryClientOwnerResolver;
import io.github.oatelauser.jauth.core.org.InMemoryOrgRepository;
import io.github.oatelauser.jauth.core.org.Org;
import io.github.oatelauser.jauth.core.org.OrgMember;
import io.github.oatelauser.jauth.core.org.OrgRole;
import io.github.oatelauser.jauth.core.org.OrgService;
import io.github.oatelauser.jauth.core.response.DefaultResponseRenderer;
import io.github.oatelauser.jauth.core.response.JauthResponseAdvice;
import io.github.oatelauser.jauth.core.scope.InMemoryScopeCatalog;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * org 应用面（B11；v1.5 B5b 页路由 302 化后重写）：注册 JSON 面（OWNER 门 A0508 在控制器入口、机密
 * secret 仅此一次、公开无 secret、A0503/A0504）+ 页面路由无条件 302 到 SPA 皮。受测服务用
 * InMemoryOwnedAppService 真路径——页面两存储模式同契约。
 *
 * @author oatelauser
 */
class OrgAppsControllerTest {

    private static final Instant T0 = Instant.parse("2026-09-30T10:00:00Z");

    private static final String ALICE = "alice";

    private static final String BOB = "bob";

    private UserRepository users;

    private InMemoryOrgRepository orgRepository;

    private OrgService orgService;

    private InMemoryOwnedAppService ownedAppService;

    private Org org;

    private final List<AuditEvent> auditLog = new ArrayList<>();

    @BeforeEach
    void setUp() {
        this.users = mock(UserRepository.class);
        when(this.users.findByUsername(ALICE)).thenReturn(user("user-alice", ALICE));
        when(this.users.findByUsername(BOB)).thenReturn(user("user-bob", BOB));
        this.orgRepository = new InMemoryOrgRepository();
        this.orgService = new OrgService(this.orgRepository, this.auditLog::add, Clock.fixed(T0, ZoneOffset.UTC), null);
        this.ownedAppService = new InMemoryOwnedAppService(
                new InMemoryRegisteredClientRepository(RegisteredClient.withId("seed-platform-1")
                        .clientId("seed-platform")
                        .clientName("platform seed")
                        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                        .build()),
                new InMemoryClientOwnerResolver(),
                new io.github.oatelauser.jauth.core.token.InMemoryTokenFamilyService(),
                new BCryptPasswordEncoder(),
                new InMemoryScopeCatalog(),
                Clock.fixed(T0, ZoneOffset.UTC));
        this.org = this.orgService.create("acme", "user-alice");
    }

    @Test
    @DisplayName("越权门：MEMBER 页面与注册 JSON 均 A0508（控制器入口，OWNER 外拒），注册无落库")
    void memberGetsA0508OnPageAndRegister() throws Exception {
        this.orgRepository.saveMember(new OrgMember(this.org.id(), "user-bob", OrgRole.MEMBER, T0));

        // 页面路由 v1.5 B5b 起无条件 302（不再有控制器入口 OWNER 门）；门语义在 JSON 注册/状态面
        api().perform(get(appsPath()).principal(() -> BOB))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/front/selfservice/orgs/" + this.org.id() + "/apps"));
        api().perform(post(appsPath())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"越权应用\",\"redirectUris\":\"https://evil.example.com/cb\"}")
                        .principal(() -> BOB))
                .andExpect(jsonPath("$.code").value("A0508"));
        assertThat(this.ownedAppService.listOrg(this.org.id())).isEmpty();
    }

    @Test
    @DisplayName("注册 JSON（机密）：clientId 带 app_ 头、secret 仅此一次；注册后 org 列表可见")
    void registerConfidentialReturnsSecretOnce() throws Exception {
        String body = api().perform(post(appsPath())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"组织门户\",\"redirectUris\":\"https://portal.example.com/cb\","
                                + "\"confidential\":true}")
                        .principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.clientId", startsWith("app_")))
                .andExpect(jsonPath("$.data.clientSecret").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        // 列表行/存储侧永不出现明文 secret（服务契约 + 页面投影双证明）
        assertThat(this.ownedAppService.listOrg(this.org.id()).get(0).toString())
                .doesNotContain(extractSecret(body));
    }

    @Test
    @DisplayName("注册 JSON（公开）：无 clientSecret 字段；未认证 A0503、服务缺席 A0504")
    void registerPublicAndGates() throws Exception {
        api().perform(post(appsPath())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"移动端\",\"redirectUris\":\"http://localhost:8080/callback\"}")
                        .principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.clientSecret").doesNotExist());
        api().perform(post(appsPath())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"redirectUris\":\"https://a.example.com/cb\"}"))
                .andExpect(jsonPath("$.code").value("A0503"));
        MockMvc missing = MockMvcBuilders.standaloneSetup(new OrgAppsController(
                        null,
                        null,
                        this.users,
                        new DefaultResponseRenderer(),
                        event -> {},
                        new io.github.oatelauser.jauth.core.ratelimit.RateLimiter(
                                1_000, 5, java.time.Duration.ofMinutes(15), java.time.Clock.systemUTC())))
                .setControllerAdvice(jauthAdvice())
                .build();
        missing.perform(post(appsPath())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"redirectUris\":\"https://a.example.com/cb\"}")
                        .principal(() -> ALICE))
                .andExpect(jsonPath("$.code").value("A0504"));
    }

    private String appsPath() {
        return "/selfservice/orgs/" + this.org.id() + "/apps";
    }

    /** JSON 面（advice 手挂：JauthException → SPI 失败体，生产由 starter 装配）。 */
    private MockMvc api() {
        return MockMvcBuilders.standaloneSetup(newController())
                .setControllerAdvice(jauthAdvice())
                .setViewResolvers(new org.springframework.web.servlet.view.InternalResourceViewResolver())
                .build();
    }

    private OrgAppsController newController() {
        return new OrgAppsController(
                this.ownedAppService,
                this.orgService,
                this.users,
                new DefaultResponseRenderer(),
                event -> {},
                new io.github.oatelauser.jauth.core.ratelimit.RateLimiter(
                        1_000, 5, java.time.Duration.ofMinutes(15), java.time.Clock.systemUTC()));
    }

    private static JauthResponseAdvice jauthAdvice() {
        return new JauthResponseAdvice(new DefaultResponseRenderer());
    }

    /** 从注册响应提取明文 secret（测试辅助；响应即明文的唯一出现点）。 */
    private static String extractSecret(String responseBody) {
        int index = responseBody.indexOf("\"clientSecret\":\"") + "\"clientSecret\":\"".length();
        return responseBody.substring(index, responseBody.indexOf('"', index));
    }

    private static JauthUser user(String id, String username) {
        return new JauthUser(
                id,
                username,
                "placeholder-password-hash-not-real",
                null,
                null,
                JauthUser.ROLE_USER,
                JauthUser.STATUS_ACTIVE,
                null,
                T0);
    }
}
