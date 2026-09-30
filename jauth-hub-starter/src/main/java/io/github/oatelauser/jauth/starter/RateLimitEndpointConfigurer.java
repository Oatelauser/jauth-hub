package io.github.oatelauser.jauth.starter;

import io.github.oatelauser.jauth.core.ratelimit.RateLimiter;
import io.github.oatelauser.jauth.core.web.RateLimitFilter;
import org.springframework.security.config.annotation.web.HttpSecurityBuilder;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.web.OAuth2ClientAuthenticationFilter;
import org.springframework.util.Assert;

/**
 * 端点限流过滤器的挂链配置器（B7）：把 {@link RateLimitFilter} 钉在客户端认证过滤器之后。
 *
 * <p><b>为什么是配置器而非链 bean 里的 addFilterAfter</b>：授权服务器的各端点过滤器（含
 * OAuth2ClientAuthenticationFilter）在 build 期由其配置器注册进顺序表，链 bean 方法体先于 build 执行，
 * 彼时锚类尚无注册序位（直接 addFilterAfter 抛 "does not have a registered order"）。本配置器在
 * oauth2AuthorizationServer 之后挂入（{@code http.with(...)}），configure 期锚类已注册，序位精确。
 *
 * <p>序位语义见 {@link RateLimitFilter} 类注释：客户端认证之后（主体名可取）、token/introspection
 * 端点过滤器之前（限流先于端点逻辑拒绝）。
 *
 * @param <H> 装配的目标 HttpSecurity 构建器
 * @author oatelauser
 */
final class RateLimitEndpointConfigurer<H extends HttpSecurityBuilder<H>>
        extends AbstractHttpConfigurer<RateLimitEndpointConfigurer<H>, H> {

    private final RateLimiter rateLimiter;

    private final AuthorizationServerSettings authorizationServerSettings;

    RateLimitEndpointConfigurer(RateLimiter rateLimiter, AuthorizationServerSettings settings) {
        Assert.notNull(rateLimiter, "rateLimiter cannot be null");
        Assert.notNull(settings, "settings cannot be null");
        this.rateLimiter = rateLimiter;
        this.authorizationServerSettings = settings;
    }

    @Override
    public void configure(H http) {
        http.addFilterAfter(
                new RateLimitFilter(
                        this.rateLimiter,
                        this.authorizationServerSettings.getTokenEndpoint(),
                        this.authorizationServerSettings.getTokenIntrospectionEndpoint()),
                OAuth2ClientAuthenticationFilter.class);
    }
}
