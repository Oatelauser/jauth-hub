package io.github.oatelauser.jauth.core.web;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.util.Assert;

/**
 * 平台令牌解析的授权令牌路径：oauth2_authorization 内的 opaque access token（本进程授权服务直查）。
 *
 * <p>有效口径与内省端点一致：命中 access token 且未失效未过期（框架 {@code Token.isActive()} 语义）。
 * sub/username 取签发时 opaque 定制器写入的 claims（与 /introspect 富化同源，票 07 内省富化决议）。
 *
 * <p>构造面取 {@code Function} 查询函数而非 OAuth2AuthorizationService 字段——与 DefaultClaimsContributor
 * 同款取舍（不把框架服务接口存成员，SpotBugs EI_EXPOSE_REP2 洁净；装配侧以方法引用注入）。
 *
 * @author oatelauser
 */
public final class AccessTokenPlatformTokenResolver implements PlatformTokenResolver {

    private final Function<String, @Nullable OAuth2Authorization> accessTokenLookup;

    public AccessTokenPlatformTokenResolver(Function<String, @Nullable OAuth2Authorization> accessTokenLookup) {
        Assert.notNull(accessTokenLookup, "accessTokenLookup cannot be null");
        this.accessTokenLookup = accessTokenLookup;
    }

    @Override
    public @Nullable MeIdentity resolve(String tokenValue) {
        Assert.hasText(tokenValue, "tokenValue cannot be empty");
        OAuth2Authorization authorization = this.accessTokenLookup.apply(tokenValue);
        if (authorization == null) {
            return null;
        }
        OAuth2Authorization.Token<OAuth2AccessToken> accessToken = authorization.getAccessToken();
        if (accessToken == null || !accessToken.isActive()) {
            return null;
        }
        Map<String, Object> claims = accessToken.getClaims();
        if (claims == null || claims.get("sub") == null) {
            return null;
        }
        return new MeIdentity(
                String.valueOf(claims.get("sub")),
                claims.get("username") != null ? String.valueOf(claims.get("username")) : null,
                scopesOf(authorization, claims),
                accessToken.getToken().getIssuedAt(),
                accessToken.getToken().getExpiresAt());
    }

    /** scope 口径：常规授权取 authorizedScopes；PAT 合成授权（无 authorizedScopes）回退 claims 的 scope 串。 */
    private static Set<String> scopesOf(OAuth2Authorization authorization, Map<String, Object> claims) {
        if (!authorization.getAuthorizedScopes().isEmpty()) {
            return Set.copyOf(authorization.getAuthorizedScopes());
        }
        Object scope = claims.get("scope");
        if (scope instanceof String joined && !joined.isBlank()) {
            return Arrays.stream(joined.trim().split("\\s+")).collect(Collectors.toUnmodifiableSet());
        }
        return Set.of();
    }
}
