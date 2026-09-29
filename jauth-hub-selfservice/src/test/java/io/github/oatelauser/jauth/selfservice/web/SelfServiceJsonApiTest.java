package io.github.oatelauser.jauth.selfservice.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.oatelauser.jauth.core.response.DefaultResponseRenderer;
import io.github.oatelauser.jauth.core.response.JauthResponseAdvice;
import io.github.oatelauser.jauth.core.scope.InMemoryScopeCatalog;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import io.github.oatelauser.jauth.selfservice.pat.InMemoryPatService;
import io.github.oatelauser.jauth.selfservice.pat.PatTokens;
import io.github.oatelauser.jauth.selfservice.support.Providers;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 自助 JSON 面：PAT 全生命周期（建 → 唯一明文回显 → 列表无明文 → 吊销 → 再吊销 B0502）+ 参数校验 + memory
 * 门控（A0504）+ 看板 revoke 的双 remove 委托。响应统一走 ResponseRenderer（00000/A05xx 码）。
 *
 * @author oatelauser
 */
class SelfServiceJsonApiTest {

    private static final String ALICE = "alice";

    private static final Instant T0 = Instant.parse("2026-09-29T10:00:00Z");

    private UserRepository users;

    private InMemoryPatService patService;

    private MockMvc patApi;

    @BeforeEach
    void setUp() {
        this.users = mock(UserRepository.class);
        when(this.users.findByUsername(ALICE)).thenReturn(user(ALICE));
        this.patService = new InMemoryPatService(Clock.fixed(T0, ZoneOffset.UTC));
        this.patApi = MockMvcBuilders.standaloneSetup(new PatController(
                        this.patService,
                        new InMemoryScopeCatalog(),
                        this.users,
                        messageSource(),
                        EducationalFlag.ON,
                        new DefaultResponseRenderer(),
                        Clock.fixed(T0, ZoneOffset.UTC)))
                .setControllerAdvice(jauthAdvice())
                .build();
    }

    /** standalone MockMvc 不带自动配置，JauthException → SPI 失败体的 advice 须手挂（生产由 starter 装配）。 */
    private static JauthResponseAdvice jauthAdvice() {
        return new JauthResponseAdvice(new DefaultResponseRenderer());
    }

    @Test
    @DisplayName("PAT 创建：唯一一次明文回显，data.prefix 与哈希锚一致，过期时间 = T0+90d")
    void createReturnsPlaintextOnce() throws Exception {
        this.patApi
                .perform(post("/selfservice/pat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopes\":[\"openid\",\"profile\"],\"validityDays\":90}")
                        .principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.token", startsWith(PatTokens.TOKEN_HEADER)))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-12-28T10:00:00Z"));

        // 列表回查：前缀在、明文绝不在（明文纪律的响应面证明）
        MvcResult created = this.patApi
                .perform(post("/selfservice/pat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopes\":[\"openid\"],\"validityDays\":30}")
                        .principal(() -> ALICE))
                .andReturn();
        String plaintext = plaintextOf(created.getResponse().getContentAsString(StandardCharsets.UTF_8));
        MvcResult listResult = this.patApi
                .perform(get("/selfservice/pat/list").principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data..token").doesNotExist())
                .andReturn();
        // 固定时钟下两条记录同刻创建，顺序以 id 决胜——断言"包含前缀、不含明文"即可
        String listBody = listResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(listBody).contains("\"prefix\":\"" + plaintext.substring(0, PatTokens.DISPLAY_PREFIX_LENGTH) + "\"");
        assertThat(listBody).doesNotContain(plaintext);
    }

    @Test
    @DisplayName("PAT 吊销：成功后列表清空，再吊销回 B0502")
    void revokeClearsListAndSecondRevokeFails() throws Exception {
        String patId = this.patService
                .create("user-alice", Set.of("openid"), Duration.ofDays(90))
                .record()
                .id();

        this.patApi
                .perform(post("/selfservice/pat/{id}/revoke", patId).principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));

        this.patApi
                .perform(get("/selfservice/pat/list").principal(() -> ALICE))
                .andExpect(jsonPath("$.data.length()").value(0));

        this.patApi
                .perform(post("/selfservice/pat/{id}/revoke", patId).principal(() -> ALICE))
                .andExpect(jsonPath("$.code").value("B0502"));
    }

    @Test
    @DisplayName("PAT 参数校验：目录外 scope / 空 scope / 非法有效期")
    void createValidatesScopesAndValidity() throws Exception {
        this.patApi
                .perform(post("/selfservice/pat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopes\":[\"openid\",\"write:admin\"],\"validityDays\":90}")
                        .principal(() -> ALICE))
                .andExpect(jsonPath("$.code").value("A0505"));
        this.patApi
                .perform(post("/selfservice/pat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopes\":[],\"validityDays\":90}")
                        .principal(() -> ALICE))
                .andExpect(jsonPath("$.code").value("A0505"));
        this.patApi
                .perform(post("/selfservice/pat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopes\":[\"openid\"],\"validityDays\":7}")
                        .principal(() -> ALICE))
                .andExpect(jsonPath("$.code").value("A0502"));
    }

    @Test
    @DisplayName("memory 门控：无 PatService 时 JSON 端点回 A0504（页面提示态在 PagesTest 覆盖）")
    void jsonEndpointsFailWithA0504WhenServiceMissing() throws Exception {
        MockMvc memoryMode = MockMvcBuilders.standaloneSetup(new PatController(
                        null,
                        new InMemoryScopeCatalog(),
                        this.users,
                        messageSource(),
                        EducationalFlag.ON,
                        new DefaultResponseRenderer(),
                        Clock.fixed(T0, ZoneOffset.UTC)))
                .setControllerAdvice(jauthAdvice())
                .build();
        memoryMode
                .perform(post("/selfservice/pat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopes\":[\"openid\"],\"validityDays\":90}")
                        .principal(() -> ALICE))
                .andExpect(jsonPath("$.code").value("A0504"));
        memoryMode
                .perform(get("/selfservice/pat/list").principal(() -> ALICE))
                .andExpect(jsonPath("$.code").value("A0504"));
    }

    @Test
    @DisplayName("看板 revoke：委托服务双清；未认证回 A0503")
    void appsRevokeDelegatesDoubleRemove() throws Exception {
        AuthorizedAppService appService = mock(AuthorizedAppService.class);
        OAuth2AuthorizationService authorizationService = mock(OAuth2AuthorizationService.class);
        OAuth2AuthorizationConsentService consentService = mock(OAuth2AuthorizationConsentService.class);
        OAuth2Authorization authorization = mock(OAuth2Authorization.class);
        when(appService.authorizationIds(ALICE, "client-a")).thenReturn(List.of("auth-row-1"));
        when(authorizationService.findById("auth-row-1")).thenReturn(authorization);
        MockMvc appsApi = MockMvcBuilders.standaloneSetup(new AuthorizedAppsController(
                        appService,
                        Providers.fixed(authorizationService),
                        Providers.fixed(consentService),
                        mock(
                                org.springframework.security.oauth2.server.authorization.client
                                        .RegisteredClientRepository.class),
                        EducationalFlag.ON,
                        new DefaultResponseRenderer()))
                .setControllerAdvice(jauthAdvice())
                .build();

        appsApi.perform(post("/selfservice/apps/client-a/revoke").principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));
        // 双 remove 委托：授权行经框架授权服务删除 + consent 查询同腿执行
        verify(appService).authorizationIds(ALICE, "client-a");
        verify(authorizationService).remove(authorization);
        verify(consentService).findById("client-a", ALICE);

        appsApi.perform(post("/selfservice/apps/client-a/revoke"))
                .andExpect(jsonPath("$.code").value("A0503"));
    }

    /** 从创建响应提取明文令牌（测试辅助；响应即明文的唯一出现点）。 */
    private static String plaintextOf(String responseBody) {
        int tokenIndex = responseBody.indexOf("\"token\":\"") + "\"token\":\"".length();
        return responseBody.substring(tokenIndex, responseBody.indexOf('"', tokenIndex));
    }

    private MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("io/github/oatelauser/jauth/selfservice/i18n/messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        return source;
    }

    private JauthUser user(String username) {
        return new JauthUser(
                "user-" + username,
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
