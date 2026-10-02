package io.github.oatelauser.jauth.app.user;

import io.github.oatelauser.jauth.core.audit.AuditEvent;
import io.github.oatelauser.jauth.core.audit.AuditEventPublisher;
import io.github.oatelauser.jauth.core.audit.AuditEventType;
import io.github.oatelauser.jauth.core.authorization.PrincipalAuthorizationRevoker;
import io.github.oatelauser.jauth.selfservice.pat.PatService;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.Assert;

/**
 * 账号状态变更的清剿编排（v1.3 D1，fail-secure 口径经用户拍板）：
 *
 * <ul>
 *   <li>自助改密：全量授权清剿 + 会话全失效<b>保留当前会话</b>；PAT 保留（PAT 独立于口令，GitHub 同款）</li>
 *   <li>管理员重置密码：目标用户全量授权清剿 + 会话全失效（不保留）；PAT 保留</li>
 *   <li>停用：全量授权清剿 + 会话全失效 + <b>PAT 全撤销</b>——账号死则其凭据全死；启用方向不清剿</li>
 * </ul>
 *
 * <p>PAT 走 {@link ObjectProvider}：memory 存储模式 PAT 禁用无 bean（SPEC §3），缺席即 0。
 * 汇总审计（credentials.revoked，detail 记 reason 与三级计数）在此发布；逐令牌 token.revoked
 * 由清剿路径自身产生（memory 装配逐条 remove 可见，jdbc 直删路径无逐条事件）。
 *
 * @author oatelauser
 */
public class AccountSecurityService {

    private final PrincipalAuthorizationRevoker authorizationRevoker;

    private final UserSessionInvalidator sessionInvalidator;

    private final ObjectProvider<PatService> patServices;

    private final AuditEventPublisher auditPublisher;

    public AccountSecurityService(
            PrincipalAuthorizationRevoker authorizationRevoker,
            UserSessionInvalidator sessionInvalidator,
            ObjectProvider<PatService> patServices,
            AuditEventPublisher auditPublisher) {
        Assert.notNull(authorizationRevoker, "authorizationRevoker cannot be null");
        Assert.notNull(sessionInvalidator, "sessionInvalidator cannot be null");
        Assert.notNull(patServices, "patServices cannot be null");
        Assert.notNull(auditPublisher, "auditPublisher cannot be null");
        this.authorizationRevoker = authorizationRevoker;
        this.sessionInvalidator = sessionInvalidator;
        this.patServices = patServices;
        this.auditPublisher = auditPublisher;
    }

    /**
     * 凭据变更（自助改密 / 管理员重置）后的清剿。
     *
     * @param principalName 目标主体名（登录名）
     * @param targetUserId 目标用户 id
     * @param actingUserId 行为人 id（自助=目标本人；管理员操作=管理员）
     * @param keepSessionId 保留的会话 id（自助改密传当前会话；管理员重置 null）
     * @return 三级计数
     */
    public RevocationSummary onCredentialsChanged(
            String principalName, String targetUserId, String actingUserId, @Nullable String keepSessionId) {
        return revoke(principalName, targetUserId, actingUserId, keepSessionId, false, "password_changed");
    }

    /**
     * 停用后的清剿（PAT 一并撤销）。
     *
     * @param principalName 目标主体名
     * @param targetUserId 目标用户 id
     * @param actingUserId 操作管理员 id
     * @return 三级计数
     */
    public RevocationSummary onUserDisabled(String principalName, String targetUserId, String actingUserId) {
        return revoke(principalName, targetUserId, actingUserId, null, true, "user_disabled");
    }

    private RevocationSummary revoke(
            String principalName,
            String targetUserId,
            String actingUserId,
            @Nullable String keepSessionId,
            boolean revokePats,
            String reason) {
        int authorizations = this.authorizationRevoker.revokeAll(principalName);
        int sessions = this.sessionInvalidator.invalidateAll(principalName, keepSessionId);
        int pats = revokePats ? revokeAllPats(targetUserId) : 0;
        RevocationSummary summary = new RevocationSummary(authorizations, sessions, pats);
        this.auditPublisher.publish(AuditEvent.of(
                AuditEventType.CREDENTIALS_REVOKED,
                actingUserId,
                "user",
                targetUserId,
                "reason=" + reason + " authorizations=" + authorizations + " sessions=" + sessions + " pats=" + pats));
        return summary;
    }

    private int revokeAllPats(String targetUserId) {
        PatService patService = this.patServices.getIfAvailable();
        return patService == null ? 0 : patService.revokeAllForUser(targetUserId);
    }

    /**
     * 清剿计数（审计 detail 与测试断言共用口径）。
     *
     * @param authorizations 删除的授权数
     * @param sessions 失效的会话数
     * @param pats 撤销的 PAT 数
     */
    public record RevocationSummary(int authorizations, int sessions, int pats) {}
}
