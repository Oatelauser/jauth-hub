package io.github.oatelauser.jauth.resourceserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionAuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 装配面（ApplicationContextRunner）：三凭证齐备才激活、@ConditionalOnMissingBean 让位、
 * 薄封装红线（绝不产出 SecurityFilterChain）、缓存默认值落位。
 *
 * @author oatelauser
 */
class JauthResourceServerAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JauthResourceServerAutoConfiguration.class));

    private static final String[] CREDENTIAL_PROPERTIES = {
        "jauth-hub.rs.introspection-uri=https://jauth.example.com/introspect",
        "jauth-hub.rs.client-id=rs-client",
        "jauth-hub.rs.client-secret=placeholder-test-secret-not-real",
    };

    @Test
    @DisplayName("凭证缺位：starter 整体休眠，不产出任何 bean（含 SecurityFilterChain）")
    void dormantWithoutCredentials() {
        this.runner.run(context -> {
            assertThat(context).doesNotHaveBean(OpaqueTokenIntrospector.class);
            assertThat(context).doesNotHaveBean(JauthResourceServerConfigurer.class);
            assertThat(context.getBeansOfType(SecurityFilterChain.class)).isEmpty();
        });
    }

    @Test
    @DisplayName("凭证齐备：CachingIntrospector + 便捷 Customizer 在场，默认 30s/1000，且不建 SecurityFilterChain")
    void wiresWithCredentials() {
        this.runner.withPropertyValues(CREDENTIAL_PROPERTIES).run(context -> {
            assertThat(context).hasBean("jauthOpaqueTokenIntrospector");
            assertThat(context.getBean(OpaqueTokenIntrospector.class)).isInstanceOf(CachingIntrospector.class);
            assertThat(context).hasBean("jauthResourceServerConfigurer");
            JauthResourceServerConfigurer configurer = context.getBean(JauthResourceServerConfigurer.class);
            assertThat(configurer.getIntrospector()).isSameAs(context.getBean(OpaqueTokenIntrospector.class));
            JauthResourceServerProperties properties = context.getBean(JauthResourceServerProperties.class);
            assertThat(properties.getCache().getTtl()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.getCache().getNegativeTtl()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.getCache().getMaxEntries()).isEqualTo(1000);
            assertThat(properties.getAuthorityPrefix()).isEmpty();
            // 薄封装红线：宿主链共存——本 starter 绝不注册安全链（SPEC §2 / 06/08 票）
            assertThat(context.getBeansOfType(SecurityFilterChain.class)).isEmpty();
        });
    }

    @Test
    @DisplayName("宿主自定义内省器：introspector 让位（无缓存包装），Customizer 改接宿主实现")
    void hostIntrospectorTakesPrecedence() {
        this.runner
                .withPropertyValues(CREDENTIAL_PROPERTIES)
                .withUserConfiguration(HostIntrospectorConfiguration.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(CachingIntrospector.class);
                    assertThat(context.getBean(OpaqueTokenIntrospector.class))
                            .isInstanceOf(HostIntrospectorConfiguration.HostIntrospector.class);
                    assertThat(context.getBean(JauthResourceServerConfigurer.class)
                                    .getIntrospector())
                            .isInstanceOf(HostIntrospectorConfiguration.HostIntrospector.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class HostIntrospectorConfiguration {

        @Bean
        OpaqueTokenIntrospector hostIntrospector() {
            return new HostIntrospector();
        }

        static final class HostIntrospector implements OpaqueTokenIntrospector {

            @Override
            public OAuth2AuthenticatedPrincipal introspect(String token) {
                return new OAuth2IntrospectionAuthenticatedPrincipal("host", Map.of(), List.of());
            }
        }
    }
}
