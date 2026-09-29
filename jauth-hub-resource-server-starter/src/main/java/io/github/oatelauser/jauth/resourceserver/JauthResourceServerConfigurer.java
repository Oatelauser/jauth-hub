package io.github.oatelauser.jauth.resourceserver;

import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.resource.OAuth2ResourceServerConfigurer;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;

/**
 * 宿主接入便捷 Customizer：把预接线的 {@link OpaqueTokenIntrospector} 装进宿主自己的资源服务器配置。
 *
 * <p>薄封装红线（SPEC §2 宿主链共存 / 06/08 票裁定）：本 starter 绝不创建自己的
 * {@code SecurityFilterChain}——安全链归属宿主，这里只省掉宿主的一行 lambda：
 *
 * <pre>{@code
 * http.oauth2ResourceServer(jauthResourceServerConfigurer);
 * // 等价于 http.oauth2ResourceServer(o -> o.opaqueToken(t -> t.introspector(introspector)));
 * }</pre>
 *
 * <p>401/403 渲染同样归宿主（标准 Spring Security 异常语义，本 starter 不接管、不包装错误码）。
 *
 * @author oatelauser
 */
public final class JauthResourceServerConfigurer implements Customizer<OAuth2ResourceServerConfigurer<HttpSecurity>> {

    private final OpaqueTokenIntrospector introspector;

    public JauthResourceServerConfigurer(OpaqueTokenIntrospector introspector) {
        this.introspector = introspector;
    }

    public OpaqueTokenIntrospector getIntrospector() {
        return this.introspector;
    }

    @Override
    public void customize(OAuth2ResourceServerConfigurer<HttpSecurity> resourceServer) {
        resourceServer.opaqueToken(opaque -> opaque.introspector(this.introspector));
    }
}
