package io.github.oatelauser.jauth.core.web;

import java.time.Instant;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 平台 API 令牌解析 SPI（/me 的认证面，SPEC §4 端点三分：/me 是平台 API 裸 JSON）。
 *
 * <p>Bearer opaque token 的<b>本进程内省</b>（不走 HTTP 环回）：授权令牌（oauth2_authorization 的
 * access token）由默认实现解析；PAT 由装配方按存储模式叠加（jdbc 模式查 jauth_pat）。解析失效/过期/
 * 吊销/未知一律返回 null——调用方（{@link MeController}）统一按 RFC 6750 拒 401。
 *
 * @author oatelauser
 */
public interface PlatformTokenResolver {

    /**
     * 解析一枚 Bearer 令牌值。
     *
     * @param tokenValue 令牌明文（Authorization 头去 Bearer 前缀）
     * @return 有效令牌的身份投影；无效/未知返回 null
     */
    @Nullable
    MeIdentity resolve(String tokenValue);

    /**
     * /me 响应所需的身份投影（裸 JSON 三字段的数据源，兼作
     * {@link LocalIntrospectionJwtDecoder} 的内省命中形状）。
     *
     * @param sub 稳定用户标识（jauth_user.id，与内省 sub 同口径）
     * @param username 登录名
     * @param scopes 授权 scope 集
     * @param issuedAt 令牌签发时刻（无值可空）
     * @param expiresAt 令牌过期时刻（无值可空）
     */
    record MeIdentity(
            String sub, String username, Set<String> scopes, @Nullable Instant issuedAt, @Nullable Instant expiresAt) {

        /** scopes 防御性拷贝为不可变集（记录出入均不可被外部改动）。 */
        public MeIdentity {
            scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        }
    }
}
