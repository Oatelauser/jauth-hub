package io.github.oatelauser.jauth.starter;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oatelauser.jauth.core.scope.ScopeCatalog;
import io.github.oatelauser.jauth.core.scope.ScopeDefinition;
import io.github.oatelauser.jauth.core.web.RequiresScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code @RequiresScope} 自动注册装配测试（v1.2 C4 ①，矩阵 runner 面）：同 JVM 桩控制器带注解 →
 * scope 进目录（name/sensitive/desc/i18nKey 全对）；无注解 context → 零注册（目录只有内置三枚）。
 * 加载 WebMvcAutoConfiguration 供 RequestMappingHandlerMapping（registrar 经 provider 取映射）。
 *
 * @author oatelauser
 */
class RequiresScopeAutoRegistrationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JauthSpringPlusBridgeAutoConfiguration.class,
                    JauthHubAutoConfiguration.class,
                    WebMvcAutoConfiguration.class))
            .withUserConfiguration(WebSecurityBase.class)
            .withPropertyValues(
                    "jauth-hub.clients[0].client-id=scope-client",
                    "jauth-hub.clients[0].client-name=Scope Client",
                    "jauth-hub.clients[0].client-secret=scope-secret",
                    "jauth-hub.clients[0].grant-types[0]=authorization_code",
                    "jauth-hub.clients[0].redirect-uris[0]=https://example.com/cb",
                    "jauth-hub.clients[0].scopes[0]=openid");

    @Test
    @DisplayName("带注解桩控制器：scope 自动进目录，name/sensitive/desc/i18nKey 全对")
    void annotatedHandlerRegistersScopeIntoCatalog() {
        this.runner.withUserConfiguration(StubScopedControllerConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            ScopeDefinition definition =
                    context.getBean(ScopeCatalog.class).find("stub:read").orElseThrow();
            assertThat(definition.sensitive()).isTrue();
            assertThat(definition.fallbackDesc()).isEqualTo("桩读取");
            assertThat(definition.i18nKey()).isEqualTo("jauth.scope.stub:read");
        });
    }

    @Test
    @DisplayName("无注解 context 零注册：目录只有内置三枚（非注解 HandlerMethod 不产生条目）")
    void contextWithoutAnnotationRegistersNothing() {
        this.runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ScopeCatalog.class).all()).hasSize(3);
        });
    }

    /** 桩控制器挂载：同 JVM 宿主自有受 scope 保护接口的最小形态（runner 隔离，不进组件扫描）。 */
    @Configuration(proxyBeanMethods = false)
    static class StubScopedControllerConfig {

        @Bean
        StubScopedController stubScopedController() {
            return new StubScopedController();
        }
    }

    @RestController
    static class StubScopedController {

        @GetMapping("/stub/scoped")
        @RequiresScope(value = "stub:read", desc = "桩读取", sensitive = true)
        String scoped() {
            return "ok";
        }
    }

    /**
     * HttpSecurity 基座（照矩阵测试同型）：HttpSecurity/WebSecurity 装配不在 Boot 自动配置清单内，
     * 由 @EnableWebSecurity 拉起（@SpringBootTest 场景里 Boot 的安全自动配置承担同一角色）。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class WebSecurityBase {

        @Bean
        UserDetailsService userDetailsService() {
            return new InMemoryUserDetailsManager(User.withUsername("user")
                    .password("{noop}password")
                    .roles("USER")
                    .build());
        }
    }
}
