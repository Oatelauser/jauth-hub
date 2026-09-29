package io.github.oatelauser.jauth.resourceserver;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.web.client.RestClient;

/**
 * rs-starter 自动装配（SPEC §2：薄封装预接线）。
 *
 * <p>激活条件 = {@code jauth-hub.rs.introspection-uri/client-id/client-secret} 三项齐备（06 票：内省调用方
 * 须为机密客户端）；缺任一项整体休眠，宿主行为不受影响。宿主自定义 {@link OpaqueTokenIntrospector} bean
 * 时全部让位（含缓存装饰——缓存语义是我们对自家内省器的裁定，不强加给宿主实现）。
 *
 * <p>红线：此处绝不注册 {@code SecurityFilterChain}，只供
 * {@link OpaqueTokenIntrospector} 与 {@link JauthResourceServerConfigurer}（一行接入）两个 bean；401/403
 * 渲染归宿主的标准 Spring Security 异常语义（06/08 票裁定）。
 *
 * @author oatelauser
 */
@AutoConfiguration
@ConditionalOnClass(OpaqueTokenIntrospector.class)
@ConditionalOnProperty(
        prefix = "jauth-hub.rs",
        name = {"introspection-uri", "client-id", "client-secret"})
@EnableConfigurationProperties(JauthResourceServerProperties.class)
public class JauthResourceServerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(OpaqueTokenIntrospector.class)
    CachingIntrospector jauthOpaqueTokenIntrospector(
            JauthResourceServerProperties properties,
            ObjectProvider<RestClient.Builder> restClientBuilders,
            ObjectProvider<Clock> clocks) {
        // getIfAvailable 回退裸 builder/系统时钟：宿主未定制也能用，不强求 Boot 的 RestClient 自动配置在场
        RestClient restClient =
                restClientBuilders.getIfAvailable(RestClient::builder).build();
        JauthOpaqueTokenIntrospector introspector = new JauthOpaqueTokenIntrospector(
                restClient,
                properties.getIntrospectionUri(),
                properties.getClientId(),
                properties.getClientSecret(),
                properties.getAuthorityPrefix());
        return new CachingIntrospector(
                introspector,
                properties.getCache().getTtl(),
                properties.getCache().getNegativeTtl(),
                properties.getCache().getMaxEntries(),
                clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean(JauthResourceServerConfigurer.class)
    JauthResourceServerConfigurer jauthResourceServerConfigurer(ObjectProvider<OpaqueTokenIntrospector> introspectors) {
        // 宿主自定义内省器也让位了 introspector bean：此处解析到的即宿主的（无缓存包装，宿主自担）
        return new JauthResourceServerConfigurer(introspectors.getObject());
    }
}
