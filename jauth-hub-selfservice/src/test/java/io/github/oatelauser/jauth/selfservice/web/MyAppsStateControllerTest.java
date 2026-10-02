package io.github.oatelauser.jauth.selfservice.web;

import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.oatelauser.jauth.core.response.DefaultResponseRenderer;
import io.github.oatelauser.jauth.core.response.JauthResponseAdvice;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import io.github.oatelauser.jauth.selfservice.web.OwnedAppService.OwnedApp;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 我的应用页 JSON 状态面（v1.5 B1a，my-app-new 页复用）：契约（00000 + OwnedApp 行 + csrf 对）、降级态
 * （服务缺席/非池主体同空列表，同 SSR 页取舍）。行装配为 {@code OwnedAppService.list} 直出（SSR 页模型
 * 同形），无二次装配可复制。认证面归部署方 default 链，本测试不设认证面。
 *
 * @author oatelauser
 */
class MyAppsStateControllerTest {

    private static final Instant T0 = Instant.parse("2026-10-02T10:00:00Z");

    private static final String ALICE = "alice";

    private UserRepository users;

    @BeforeEach
    void setUp() {
        this.users = mock(UserRepository.class);
        when(this.users.findByUsername(ALICE)).thenReturn(user());
    }

    @Test
    @DisplayName("契约：00000 + educational/appsSupported + OwnedApp 行（含 redirectUris）+ csrf 对")
    void stateReturnsContractFields() throws Exception {
        stateApi(service())
                .perform(get("/api/selfservice/my-apps").principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.educational").value(true))
                .andExpect(jsonPath("$.data.appsSupported").value(true))
                .andExpect(jsonPath("$.data.apps[0].id").value("app-1"))
                .andExpect(jsonPath("$.data.apps[0].clientId").value("client-own-1"))
                .andExpect(jsonPath("$.data.apps[0].name").value("个人小工具"))
                .andExpect(jsonPath("$.data.apps[0].confidential").value(false))
                .andExpect(jsonPath("$.data.apps[0].redirectUris[0]").value("https://tool.example.com/cb"))
                .andExpect(jsonPath("$.data.apps[0].createdAt").value(T0.toString()))
                .andExpect(jsonPath("$.data.csrfToken").value(not(emptyString())))
                .andExpect(jsonPath("$.data.csrfHeaderName").value("X-CSRF-TOKEN"));
    }

    @Test
    @DisplayName("降级：服务缺席 appsSupported=false；非池主体与未认证同空列表态（同 SSR 页取舍，不 500）")
    void degradedStatesRenderEmptyApps() throws Exception {
        stateApi(null)
                .perform(get("/api/selfservice/my-apps").principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appsSupported").value(false))
                .andExpect(jsonPath("$.data.apps").isEmpty());
        stateApi(service())
                .perform(get("/api/selfservice/my-apps").principal(() -> "not-in-pool"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appsSupported").value(true))
                .andExpect(jsonPath("$.data.apps").isEmpty());
        stateApi(service())
                .perform(get("/api/selfservice/my-apps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apps").isEmpty());
    }

    private static OwnedAppService service() {
        OwnedAppService service = mock(OwnedAppService.class);
        when(service.list("user-alice"))
                .thenReturn(List.of(new OwnedApp(
                        "app-1", "client-own-1", "个人小工具", false, List.of("https://tool.example.com/cb"), T0)));
        return service;
    }

    /** 状态面 standalone（advice 手挂 + CSRF 过滤器，同家族测试惯例）。 */
    private MockMvc stateApi(@Nullable OwnedAppService service) {
        return MockMvcBuilders.standaloneSetup(new MyAppsStateController(
                        service, this.users, EducationalFlag.ON, new DefaultResponseRenderer()))
                .setControllerAdvice(new JauthResponseAdvice(new DefaultResponseRenderer()))
                .addFilters(new CsrfFilter(new HttpSessionCsrfTokenRepository()))
                .build();
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
}
