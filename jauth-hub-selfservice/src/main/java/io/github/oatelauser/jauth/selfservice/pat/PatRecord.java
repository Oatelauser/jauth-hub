package io.github.oatelauser.jauth.selfservice.pat;

import java.time.Instant;
import java.util.Set;

/**
 * PAT 记录（表 jauth_pat 的域投影，V2 建表）。
 *
 * <p>安全纪律（SPEC §3 jauth_pat 行）：本记录与落库行一样<b>永不携带明文令牌</b>——只有 token_sha256（存侧，
 * 不出查询面）与展示前缀 {@code tokenPrefix}。明文仅在 {@link PatService#create} 的返回值中出现一次。
 *
 * <p>last_used 本批恒为 null（留位列）：B7 审计/内省接线时回填，届时 PAT 校验路径负责更新。
 *
 * @author oatelauser
 */
public record PatRecord(
        String id,
        String userId,
        String tokenPrefix,
        Set<String> scopes,
        PatStatus status,
        Instant createdAt,
        Instant expiresAt,
        Instant lastUsedAt) {

    /** scopes 防御性拷贝为不可变集（SpotBugs EI_EXPOSE_REP：记录出入均不可再被外部改动）。 */
    public PatRecord {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    /**
     * 过期判定：时间驱动而非状态驱动（吊销才是状态，见 {@link PatStatus}）。
     *
     * @param now 判定时点
     * @return true 表示已过期（列表可继续展示，标注过期徽标）
     */
    public boolean expired(Instant now) {
        return expiresAt.isBefore(now);
    }
}
