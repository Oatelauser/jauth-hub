package io.github.oatelauser.jauth.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.oatelauser.jauth.app.JauthHubAppApplication;
import io.github.oatelauser.jauth.resourceserver.JauthResourceServerProperties;
import io.github.oatelauser.jauth.starter.JauthHubProperties;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * /demo 教学区 demoConfig 状态面集成测试（v1.5 B1c）：契约（00000 + 十字段全在场 + educational，无 csrf
 * 对——契约家族有意缺席断言）、匿名可达（教学流程登录前取配置；SSR 页 ${demoConfig} 内联同面公开）、装配与
 * {@link DemoController} 同源同值（共用提取 statics，不复制）。
 *
 * @author oatelauser
 */
@SpringBootTest(classes = JauthHubAppApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:jauth-demo-config-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "jauth-hub.bootstrap.superadmin.username=superadmin",
            "jauth-hub.bootstrap.superadmin.password=super-secret-placeholder"
        })
class DemoConfigIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JauthHubProperties hubProperties;

    @Autowired
    private AuthorizationServerSettings authorizationServerSettings;

    @Autowired
    private JauthResourceServerProperties resourceServerProperties;

    @Test
    @DisplayName("契约：匿名 200 + 00000 + demoConfig 十字段全在场 + educational；无 csrf 字段（有意缺席）")
    void demoConfigStateReturnsAllFieldsWithoutCsrf() throws Exception {
        this.mockMvc
                .perform(get("/api/demo/config").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.educational").value(true))
                .andExpect(jsonPath("$.data.demoConfig.issuer").value(not(emptyString())))
                .andExpect(jsonPath("$.data.demoConfig.authorizeEndpoint").value(containsString("/oauth2/authorize")))
                .andExpect(jsonPath("$.data.demoConfig.tokenEndpoint").value(containsString("/oauth2/token")))
                .andExpect(jsonPath("$.data.demoConfig.introspectEndpoint").value(containsString("/oauth2/introspect")))
                .andExpect(jsonPath("$.data.demoConfig.clientId").value("demo-public"))
                .andExpect(jsonPath("$.data.demoConfig.redirectUri").value(containsString("/front/demo/callback")))
                .andExpect(jsonPath("$.data.demoConfig.scope").value("openid profile"))
                .andExpect(jsonPath("$.data.demoConfig.rsClientId").value("demo-rs"))
                .andExpect(jsonPath("$.data.demoConfig.rsClientSecret").value(not(emptyString())))
                .andExpect(jsonPath("$.data.demoConfig.whoamiUri").value(containsString("/api/demo/whoami")))
                .andExpect(jsonPath("$.data.csrfToken").doesNotExist())
                .andExpect(jsonPath("$.data.csrfHeaderName").doesNotExist());
    }

    @Test
    @DisplayName("装配同源同值：demoConfig 与 DemoController 提取 statics 对同批上下文 bean 出同值")
    void demoConfigMatchesDemoControllerAssembly() throws Exception {
        Map<String, Object> expected = DemoController.demoConfig(
                this.hubProperties.getIssuer(),
                this.authorizationServerSettings,
                this.resourceServerProperties.getClientSecret());

        MvcResult result = this.mockMvc
                .perform(get("/api/demo/config").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        @SuppressWarnings("unchecked")
        Map<String, Object> actual =
                JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.demoConfig");

        assertThat(actual).as("装配与 DemoController 共用 statics（不复制）").isEqualTo(expected);
    }
}
