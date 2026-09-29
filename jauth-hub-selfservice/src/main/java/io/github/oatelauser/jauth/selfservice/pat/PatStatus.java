package io.github.oatelauser.jauth.selfservice.pat;

/**
 * PAT 生命周期状态（表 jauth_pat.status 词表，词表归代码枚举所有——票 07 双库纪律）。
 *
 * <p>过期不是状态：expiry 由 expires_at 时间判定（{@link PatRecord#expired(Instant)}），DB 行保持 ACTIVE，
 * 与吊销（用户显式作废）语义分离。
 *
 * @author oatelauser
 */
public enum PatStatus {

    /** 可用（含已过期未清理的行——过期判定走时间，不走状态）。 */
    ACTIVE,

    /** 已吊销：用户显式作废，列表不再展示，令牌即刻失验。 */
    REVOKED
}
