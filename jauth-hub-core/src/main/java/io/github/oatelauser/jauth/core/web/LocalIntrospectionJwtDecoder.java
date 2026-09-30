package io.github.oatelauser.jauth.core.web;

import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.util.Assert;

/**
 * 本进程内省优先的 Bearer 解码器（/me、/userinfo 等链内 Bearer 认证面）。
 *
 * <p><b>为什么存在</b>：协议链因 OIDC userinfo 被框架强制挂上资源服务器 JWT 腿
 * （OAuth2AuthorizationServerConfigurer 对 oidc() 无条件 oauth2ResourceServer().jwt()），而本库正典令牌是
 * opaque + 内省——不接管该腿，opaque/PAT 持有者在链内一切 Bearer 面（/userinfo、/me）都会被
 * "Malformed Jwt" 401。本解码器把该腿改写为：<b>先</b>经 {@link PlatformTokenResolver} 本进程内省
 * （授权令牌 + PAT，不走 HTTP 环回），命中即合成等价 Jwt（无签名——真伪已由"库中命中且未失效"判定，
 * 这里只是给 Bearer 过滤器一个合规载体）；<b>后</b>回落真 JWT 解码（宿主注册 SELF_CONTAINED 客户端的
 * 签名访问令牌仍走签名验证）。两路皆败抛 {@link JwtException}，由过滤器按 RFC 6750 拒 401。
 *
 * @author oatelauser
 */
public final class LocalIntrospectionJwtDecoder implements JwtDecoder {

    private final PlatformTokenResolver platformTokenResolver;

    private final @Nullable JwtDecoder jwtFallback;

    public LocalIntrospectionJwtDecoder(PlatformTokenResolver platformTokenResolver, @Nullable JwtDecoder jwtFallback) {
        Assert.notNull(platformTokenResolver, "platformTokenResolver cannot be null");
        this.platformTokenResolver = platformTokenResolver;
        this.jwtFallback = jwtFallback;
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        PlatformTokenResolver.MeIdentity identity = this.platformTokenResolver.resolve(token);
        if (identity != null) {
            return syntheticJwt(token, identity);
        }
        if (this.jwtFallback != null) {
            return this.jwtFallback.decode(token);
        }
        throw new BadJwtException("Invalid token");
    }

    /**
     * 内省命中的等价 Jwt：claims 携带 sub/username/scope（DefaultJwtAuthenticationConverter 依 scope 串
     * 生成 SCOPE_ 权限），iat/exp 取自解析结果；header 占位（无签名语义，类注释）。
     */
    private static Jwt syntheticJwt(String tokenValue, PlatformTokenResolver.MeIdentity identity) {
        Instant issuedAt = identity.issuedAt() != null ? identity.issuedAt() : Instant.EPOCH;
        Instant expiresAt = identity.expiresAt() != null ? identity.expiresAt() : Instant.MAX;
        Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("sub", identity.sub());
        if (identity.username() != null) {
            claims.put("username", identity.username());
        }
        claims.put("scope", String.join(" ", identity.scopes()));
        return new Jwt(tokenValue, issuedAt, expiresAt, Map.of("alg", "none"), claims);
    }
}
