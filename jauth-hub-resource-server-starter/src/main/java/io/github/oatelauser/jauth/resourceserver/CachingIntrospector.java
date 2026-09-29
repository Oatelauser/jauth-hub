package io.github.oatelauser.jauth.resourceserver;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;

/**
 * 内省缓存装饰器：正/负结果都缓存（06 票：默认 30s，负缓存防垃圾令牌打爆内省端点）。
 *
 * <p>只负缓存 {@link JauthOpaqueTokenIntrospector.InactiveOAuth2TokenException}（端点对 active=false 的
 * 确定性判定）；端点故障类失败（网络/非 2xx/解析错）原样抛出且不缓存——否则端点抖动 30s 内会被固化成
 * "令牌全部无效"。
 *
 * <p>负缓存命中仍按框架语义抛 {@link OAuth2IntrospectionException}（{@code OpaqueTokenAuthenticationProvider}
 * 据此判 401 invalid_token），唯一区别是 30s 内不再回源内省端点。
 *
 * @author oatelauser
 */
public class CachingIntrospector implements OpaqueTokenIntrospector {

    private final OpaqueTokenIntrospector delegate;

    private final Duration ttl;

    private final Duration negativeTtl;

    private final TtlBoundedCache<Optional<OAuth2AuthenticatedPrincipal>> cache;

    public CachingIntrospector(
            OpaqueTokenIntrospector delegate, Duration ttl, Duration negativeTtl, int maxEntries, Clock clock) {
        this.delegate = delegate;
        this.ttl = ttl;
        this.negativeTtl = negativeTtl;
        this.cache = new TtlBoundedCache<>(maxEntries, clock);
    }

    @Override
    public OAuth2AuthenticatedPrincipal introspect(String token) {
        Optional<OAuth2AuthenticatedPrincipal> cached = this.cache.get(token);
        if (cached != null) {
            return cached.orElseThrow(() -> new OAuth2IntrospectionException("Token is not active"));
        }
        try {
            OAuth2AuthenticatedPrincipal principal = this.delegate.introspect(token);
            this.cache.put(token, Optional.of(principal), this.ttl);
            return principal;
        } catch (JauthOpaqueTokenIntrospector.InactiveOAuth2TokenException ex) {
            this.cache.put(token, Optional.empty(), this.negativeTtl);
            throw ex;
        }
    }
}
