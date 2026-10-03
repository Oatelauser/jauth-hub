package io.github.oatelauser.jauth.selfservice.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.oatelauser.jauth.core.ratelimit.RateLimiter;
import io.github.oatelauser.jauth.core.response.DefaultResponseRenderer;
import io.github.oatelauser.jauth.core.response.JauthResponseAdvice;
import io.github.oatelauser.jauth.core.scope.InMemoryScopeCatalog;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import io.github.oatelauser.jauth.selfservice.pat.InMemoryPatService;
import io.github.oatelauser.jauth.selfservice.pat.PatService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * PAT 页 JSON 状态面（v1.5 B1a）：契约（00000 + scope 目录服务端 i18n 描述 + 有效期阶梯 + pats 行 + now +
 * csrf 对）、行装配与既有 {@code /selfservice/pat/list} 同形同源、降级态（memory 模式 patSupported=false
 * 出空 pats 不炸）、服务在场时守"主体在池"（A0503，同 SSR 页）。认证面归部署方 default 链，本测试不设认证面。
 *
 * @author oatelauser
 */
class PatStateControllerTest {

    private static final Instant T0 = Instant.parse("2026-10-02T10:00:00Z");

    private static final String ALICE = "alice";

    private UserRepository users;

    private InMemoryPatService patService;

    @BeforeEach
    void setUp() {
        this.users = mock(UserRepository.class);
        when(this.users.findByUsername(ALICE)).thenReturn(user());
        this.patService = new InMemoryPatService(Clock.fixed(T0, ZoneOffset.UTC));
        this.patService.create("user-alice", "CI 部署", Set.of("openid"), Duration.ofDays(90));
    }

    @Test
    @DisplayName("契约：00000 + scope 目录（i18n 描述服务端解析）+ 阶梯 30/90/365 默认 90 + pats 行 + now + csrf 对")
    void stateReturnsContractFields() throws Exception {
        stateApi(this.patService)
                .perform(get("/api/selfservice/pat").principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.educational").value(true))
                .andExpect(jsonPath("$.data.patSupported").value(true))
                .andExpect(jsonPath("$.data.scopes[0].name").value("email"))
                .andExpect(jsonPath("$.data.scopes[0].description").value(not(emptyString())))
                .andExpect(jsonPath("$.data.validityDays[0]").value(30))
                .andExpect(jsonPath("$.data.validityDays[1]").value(90))
                .andExpect(jsonPath("$.data.validityDays[2]").value(365))
                .andExpect(jsonPath("$.data.defaultValidityDays").value(90))
                .andExpect(jsonPath("$.data.pats[0].name").value("CI 部署"))
                .andExpect(jsonPath("$.data.pats[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.pats[0].expired").value(false))
                .andExpect(jsonPath("$.data.pats[0].expiresAt")
                        .value(T0.plus(Duration.ofDays(90)).toString()))
                .andExpect(jsonPath("$.data.now").value(T0.toString()))
                .andExpect(jsonPath("$.data.csrfToken").value(not(emptyString())))
                .andExpect(jsonPath("$.data.csrfHeaderName").value("X-CSRF-TOKEN"));
    }

    @Test
    @DisplayName("行装配回归：pats 与既有 /selfservice/pat/list 的 data 同形同源")
    void patRowsMatchExistingListEndpoint() throws Exception {
        MvcResult listResult = listApi()
                .perform(get("/selfservice/pat/list").principal(() -> ALICE))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult stateResult = stateApi(this.patService)
                .perform(get("/api/selfservice/pat").principal(() -> ALICE))
                .andExpect(status().isOk())
                .andReturn();
        Object listData = JsonPath.read(body(listResult), "$.data");
        Object statePats = JsonPath.read(body(stateResult), "$.data.pats");
        assertThat(statePats).as("同数据源出同形状（行装配共径不复制）").isEqualTo(listData);
    }

    @Test
    @DisplayName("降级：服务缺席（memory 模式）patSupported=false + pats 空数组，目录/阶梯恒在场，不触 A0503")
    void degradedStateKeepsCatalogAndLadder() throws Exception {
        stateApi(null)
                .perform(get("/api/selfservice/pat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.patSupported").value(false))
                .andExpect(jsonPath("$.data.pats").isEmpty())
                .andExpect(jsonPath("$.data.scopes[0].name").value("email"))
                .andExpect(jsonPath("$.data.defaultValidityDays").value(90))
                .andExpect(jsonPath("$.data.now").value(T0.toString()));
    }

    @Test
    @DisplayName("服务在场守门：未认证/非池主体 A0503（同 SSR 页口径）")
    void supportedStateRequiresPoolPrincipal() throws Exception {
        stateApi(this.patService)
                .perform(get("/api/selfservice/pat"))
                .andExpect(jsonPath("$.code").value("A0503"));
    }

    /** 状态面 standalone（advice 手挂 + CSRF 过滤器，同家族测试惯例）。 */
    private MockMvc stateApi(@Nullable PatService service) {
        return MockMvcBuilders.standaloneSetup(new PatStateController(
                        service,
                        new InMemoryScopeCatalog(),
                        this.users,
                        messageSource(),
                        EducationalFlag.ON,
                        new DefaultResponseRenderer(),
                        Clock.fixed(T0, ZoneOffset.UTC)))
                .setControllerAdvice(new JauthResponseAdvice(new DefaultResponseRenderer()))
                .addFilters(new CsrfFilter(new HttpSessionCsrfTokenRepository()))
                .build();
    }

    /** 既有 /list 端点 standalone（等价性对照侧，同数据源同时钟）。 */
    private MockMvc listApi() {
        return MockMvcBuilders.standaloneSetup(new PatController(
                        this.patService,
                        new InMemoryScopeCatalog(),
                        this.users,
                        new DefaultResponseRenderer(),
                        Clock.fixed(T0, ZoneOffset.UTC),
                        new RateLimiter(1_000, 5, Duration.ofMinutes(15), Clock.systemUTC())))
                .setControllerAdvice(new JauthResponseAdvice(new DefaultResponseRenderer()))
                .build();
    }

    private static MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasenames(
                "io/github/oatelauser/jauth/selfservice/i18n/messages",
                "io/github/oatelauser/jauth/core/i18n/messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        return source;
    }

    private static JauthUser user() {
        return new JauthUser(
                "user-alice",
                ALICE,
                "placeholder-password-hash-not-real",
                null,
                null,
                JauthUser.ROLE_USER,
                JauthUser.STATUS_ACTIVE,
                null,
                T0);
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
