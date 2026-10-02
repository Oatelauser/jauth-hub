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
import io.github.oatelauser.jauth.core.response.DefaultResponseRenderer;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import io.github.oatelauser.jauth.core.web.PasskeyFlag;
import io.github.oatelauser.jauth.selfservice.support.Providers;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 看板页 JSON 状态面（v1.5 B1a）：契约（00000 + 全字段 + csrf 对）、行装配与既有 {@code /selfservice/apps/list}
 * 同形同源（同数据源出同形状）、降级态（服务缺席 appsSupported=false、未认证空列表）。认证面归部署方
 * default 链（同 SSR 页），本测试不设认证面。
 *
 * @author oatelauser
 */
class AppsStateControllerTest {

    private static final Instant T0 = Instant.parse("2026-10-02T10:00:00Z");

    private static final String ALICE = "alice";

    /** 双 client：有名可解析 + 查无回退 id（行装配的回退语义一并钉死）。 */
    private static RegisteredClientRepository clients() {
        return new InMemoryRegisteredClientRepository(RegisteredClient.withId("rc-1")
                .clientId("dashboard-client-a")
                .clientName("演示看板应用")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://placeholder.example.com/callback")
                .build());
    }

    private static AuthorizedAppService service() {
        AuthorizedAppService service = mock(AuthorizedAppService.class);
        when(service.list(ALICE))
                .thenReturn(java.util.List.of(
                        new AuthorizedApp("rc-1", Set.of("profile", "openid"), T0),
                        new AuthorizedApp("rc-unknown", Set.of("openid"), null)));
        return service;
    }

    @Test
    @DisplayName("契约：00000 + educational/appsSupported/passkeyEnabled/apps 行（scopes 有序、查无回退 id）+ csrf 对")
    void stateReturnsContractFields() throws Exception {
        stateApi(service(), (PasskeyFlag) () -> true)
                .perform(get("/api/selfservice/apps").principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.educational").value(true))
                .andExpect(jsonPath("$.data.appsSupported").value(true))
                .andExpect(jsonPath("$.data.passkeyEnabled").value(true))
                .andExpect(jsonPath("$.data.apps[0].clientId").value("rc-1"))
                .andExpect(jsonPath("$.data.apps[0].clientName").value("演示看板应用"))
                .andExpect(jsonPath("$.data.apps[0].scopes[0]").value("openid"))
                .andExpect(jsonPath("$.data.apps[0].scopes[1]").value("profile"))
                .andExpect(jsonPath("$.data.apps[1].clientId").value("rc-unknown"))
                .andExpect(jsonPath("$.data.apps[1].clientName").value("rc-unknown"))
                .andExpect(jsonPath("$.data.csrfToken").value(not(emptyString())))
                .andExpect(jsonPath("$.data.csrfHeaderName").value("X-CSRF-TOKEN"));
    }

    @Test
    @DisplayName("行装配回归：apps 与既有 /selfservice/apps/list 的 data 同形同源")
    void appsRowsMatchExistingListEndpoint() throws Exception {
        AuthorizedAppService service = service();
        MvcResult listResult = listApi(service)
                .perform(get("/selfservice/apps/list").principal(() -> ALICE))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult stateResult = stateApi(service, (PasskeyFlag) () -> true)
                .perform(get("/api/selfservice/apps").principal(() -> ALICE))
                .andExpect(status().isOk())
                .andReturn();
        Object listData = JsonPath.read(body(listResult), "$.data");
        Object stateApps = JsonPath.read(body(stateResult), "$.data.apps");
        assertThat(stateApps).as("同数据源出同形状（行装配共径不复制）").isEqualTo(listData);
    }

    @Test
    @DisplayName("降级：服务缺席 appsSupported=false + apps 空数组 + passkey 关；未认证同空列表态")
    void degradedStatesRenderEmptyApps() throws Exception {
        stateApi(null, (PasskeyFlag) () -> false)
                .perform(get("/api/selfservice/apps").principal(() -> ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appsSupported").value(false))
                .andExpect(jsonPath("$.data.passkeyEnabled").value(false))
                .andExpect(jsonPath("$.data.apps").isEmpty());
        stateApi(service(), (PasskeyFlag) () -> true)
                .perform(get("/api/selfservice/apps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appsSupported").value(true))
                .andExpect(jsonPath("$.data.apps").isEmpty());
    }

    /** 状态面 standalone（CSRF 过滤器在场即出 csrf 对；advice 手挂同家族测试惯例）。 */
    private static MockMvc stateApi(@Nullable AuthorizedAppService service, PasskeyFlag passkey) {
        return MockMvcBuilders.standaloneSetup(new AppsStateController(
                        service, clients(), EducationalFlag.ON, passkey, new DefaultResponseRenderer()))
                .setControllerAdvice(
                        new io.github.oatelauser.jauth.core.response.JauthResponseAdvice(new DefaultResponseRenderer()))
                .addFilters(new CsrfFilter(new HttpSessionCsrfTokenRepository()))
                .build();
    }

    /** 既有 /list 端点 standalone（等价性对照侧，依赖同 mock；撤销双清两 provider 在列表路径不触达）。 */
    private static MockMvc listApi(AuthorizedAppService service) {
        return MockMvcBuilders.standaloneSetup(new AuthorizedAppsController(
                        service,
                        Providers.fixed(null),
                        Providers.fixed(null),
                        clients(),
                        EducationalFlag.ON,
                        (PasskeyFlag) () -> true,
                        new DefaultResponseRenderer()))
                .setControllerAdvice(
                        new io.github.oatelauser.jauth.core.response.JauthResponseAdvice(new DefaultResponseRenderer()))
                .build();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
