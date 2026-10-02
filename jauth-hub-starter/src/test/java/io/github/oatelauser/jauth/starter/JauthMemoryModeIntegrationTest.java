package io.github.oatelauser.jauth.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.source.JWKSource;
import io.github.oatelauser.jauth.core.authorization.AuditingOAuth2AuthorizationConsentService;
import io.github.oatelauser.jauth.core.authorization.AuditingOAuth2AuthorizationService;
import io.github.oatelauser.jauth.core.response.ResponseRenderer;
import io.github.oatelauser.jauth.core.token.key.JwkRotationService;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import java.util.List;
import java.util.Locale;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * 默认装配（memory 存储）集成测试：零依赖首跑形态——协议链/renderer/播种/教学/i18n 全在场，且
 * <b>不产出 catch-all 链</b>（宿主链共存四规则之三的回归断言）。
 *
 * @author oatelauser
 */
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
class JauthMemoryModeIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private List<SecurityFilterChain> filterChains;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Autowired
    private OAuth2AuthorizationService authorizationService;

    @Autowired
    private OAuth2AuthorizationConsentService authorizationConsentService;

    @Autowired
    private AuthorizationServerSettings authorizationServerSettings;

    @Autowired
    private ObjectProvider<EducationalFlag> educationalFlagProvider;

    @Autowired
    private MessageSource messageSource;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("唯一安全链 + Boot 默认链让位：链只此一条（接管成功，无双链并存）")
    void singleFilterChain() {
        assertThat(filterChains).hasSize(1);
    }

    @Test
    @DisplayName("无 anyRequest 兜底语义：链精确认领协议端点与自有页面，未认领路径不归宿链")
    void chainClaimsExactPathsOnly() {
        SecurityFilterChain chain = filterChains.get(0);
        assertThat(chain.matches(request("POST", "/oauth2/token"))).as("协议端点").isTrue();
        assertThat(chain.matches(request("GET", "/oauth2/authorize")))
                .as("协议端点")
                .isTrue();
        assertThat(chain.matches(request("GET", "/login"))).as("登录页").isTrue();
        assertThat(chain.matches(request("GET", "/oauth2/consent")))
                .as("consent 页")
                .isTrue();
        assertThat(chain.matches(request("GET", "/device/verify"))).as("设备验证页").isTrue();
        assertThat(chain.matches(request("GET", "/api/consent")))
                .as("consent 状态面")
                .isTrue();
        assertThat(chain.matches(request("GET", "/api/device/verify")))
                .as("设备验证状态面")
                .isTrue();
        assertThat(chain.matches(request("GET", "/css/jauth.css")))
                .as("core CSS")
                .isTrue();
        assertThat(chain.matches(request("POST", "/webauthn/register/options")))
                .as("passkey 默认关：webauthn 端点不认领")
                .isFalse();
        assertThat(chain.matches(request("POST", "/login/webauthn")))
                .as("passkey 默认关：passkey 登录端点不认领")
                .isFalse();
        assertThat(chain.matches(request("GET", "/api/foo"))).as("宿主路径绝不吞").isFalse();
        assertThat(chain.matches(request("GET", "/anything/else")))
                .as("任意未认领路径")
                .isFalse();
    }

    @Test
    @DisplayName("passkey 默认关（零影响）：端点不在链上即 404，凭据仓储 bean 不装配")
    void passkeyDisabledByDefault() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/webauthn/register/options"))
                .andExpect(MockMvcResultMatchers.status().isNotFound());
        assertThat(applicationContext.getBeanNamesForType(
                        org.springframework.security.web.webauthn.management.UserCredentialRepository.class))
                .isEmpty();
        assertThat(applicationContext.getBeanNamesForType(
                        org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository
                                .class))
                .isEmpty();
    }

    @Test
    @DisplayName("memory 三件在场：InMemory client + 族谱包装与审计装饰的授权/consent 服务")
    void memoryStorageBeans() {
        assertThat(registeredClientRepository).isInstanceOf(InMemoryRegisteredClientRepository.class);
        // B7：两服务被审计装饰包裹（生命周期事件），断言到装饰层
        assertThat(authorizationConsentService).isInstanceOf(AuditingOAuth2AuthorizationConsentService.class);
        assertThat(authorizationService).isInstanceOf(AuditingOAuth2AuthorizationService.class);
    }

    @Test
    @DisplayName("memory 模式无轮转调度（密钥短命既定语义），JWK 源与令牌生成器在场")
    void noRotationInMemoryMode() {
        assertThat(applicationContext.getBeanNamesForType(JwkRotationService.class))
                .isEmpty();
        assertThat(applicationContext.getBeanNamesForType(JWKSource.class)).hasSize(1);
        assertThat(applicationContext.getBeanNamesForType(OAuth2TokenGenerator.class))
                .hasSize(1);
    }

    @Test
    @DisplayName("播种生效：种子 client 已入活动仓库")
    void seedingRan() {
        assertThat(registeredClientRepository.findByClientId("demo-client")).isNotNull();
    }

    @Test
    @DisplayName("渲染器/教学层/设置：SimpleResponse 在 classpath → 桥渲染器接管、教学开、verification_uri 落 /device/verify")
    void rendererEducationalSettings() {
        // test classpath 有 spring-plus-web（桥条件成立）；回落默认渲染器的分支由装配矩阵 FilteredClassLoader 覆盖
        assertThat(applicationContext.getBean(ResponseRenderer.class))
                .isInstanceOf(JauthSpringPlusBridgeAutoConfiguration.SimpleResponseRenderer.class);
        assertThat(educationalFlagProvider.getObject().enabled()).isTrue();
        assertThat(authorizationServerSettings.getDeviceVerificationEndpoint()).isEqualTo("/device/verify");
        assertThat(authorizationServerSettings.getIssuer()).isEqualTo("http://localhost:9000");
    }

    @Test
    @DisplayName("i18n 复合源：context messageSource 解析 core 命名空间的 jauth.* 键（模板文案的解析通道）")
    void compositeMessageSourceResolvesCoreKeys() {
        assertThat(messageSource.getMessage("jauth.brand", null, Locale.ROOT)).isEqualTo("jauth-hub");
    }

    @Test
    @DisplayName("登录页可用：匿名 GET /login 渲染 200，品牌名来自 core 模板命名空间")
    void loginPageRenders() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/login"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.content().string(Matchers.containsString("jauth-hub")));
    }

    @Test
    @DisplayName("未认证访问受保护页面重定向登录页（formLogin 接线正确）")
    void protectedPageRedirectsToLogin() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/device/verify"))
                .andExpect(MockMvcResultMatchers.status().is3xxRedirection())
                .andExpect(MockMvcResultMatchers.redirectedUrl("/login"));
    }

    @Test
    @DisplayName("CORS 默认关：未配置来源时协议端点不注册 CORS 配置")
    void corsDisabledByDefault() {
        // 按名取本装配的源：MVC 的 HandlerMappingIntrospector 也实现 CorsConfigurationSource，按类型取会歧义
        CorsConfigurationSource corsSource =
                applicationContext.getBean("corsConfigurationSource", CorsConfigurationSource.class);
        assertThat(corsSource.getCorsConfiguration(request("POST", "/oauth2/token")))
                .isNull();
    }

    private static MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    /** 无额外 bean：本测试就是"纯引入 starter 的裸宿主"形态。占位保持 @SpringBootTest classes 显式。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class BareHostMarker {}
}
