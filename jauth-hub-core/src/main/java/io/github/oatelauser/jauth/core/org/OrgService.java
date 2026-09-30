package io.github.oatelauser.jauth.core.org;

import io.github.oatelauser.jauth.core.audit.AuditEvent;
import io.github.oatelauser.jauth.core.audit.AuditEventPublisher;
import io.github.oatelauser.jauth.core.audit.AuditEventType;
import io.github.oatelauser.jauth.core.response.JauthErrorCode;
import io.github.oatelauser.jauth.core.response.JauthException;
import io.github.oatelauser.jauth.core.util.UuidV7;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.util.Assert;

/**
 * org 域服务：自助创建（任何登录用户可建，创建者自动成为 OWNER，2026-09-30 拍板）+ 查询 + OWNER 门。
 *
 * <p>创建的 org 与成员两写非原子（无事务包裹）：jauth_org 落库后成员写失败会留下无主 org，
 * TransactionTemplate 收口留 B10 统一处理；本批调用方（域测试/后续 B11 页面）皆为单线程路径。
 *
 * <p>成员增删管理面（邀请/移除/转让）不做：无调用方，YAGNI，B11 有页面语义时再定。
 *
 * @author oatelauser
 */
public class OrgService {

    private final OrgRepository orgRepository;

    private final AuditEventPublisher auditPublisher;

    private final Clock clock;

    public OrgService(OrgRepository orgRepository, AuditEventPublisher auditPublisher, Clock clock) {
        this.orgRepository = orgRepository;
        this.auditPublisher = auditPublisher;
        this.clock = clock;
    }

    /**
     * 自助创建 org：创建者自动成为 OWNER 成员，发布 org.created 审计。
     *
     * <p>重名拒绝走先查后插 + 唯一约束兜底（并发窗口内撞约束同样翻译为业务异常）。
     *
     * @param name 组织名（全局唯一）
     * @param creatorUserId 创建者（即首任 OWNER，登录主体）
     * @return 已落库的 org
     */
    public Org create(String name, String creatorUserId) {
        Assert.hasText(name, "name cannot be empty");
        Assert.hasText(creatorUserId, "creatorUserId cannot be empty");
        if (this.orgRepository.findByName(name) != null) {
            throw new JauthException(JauthErrorCode.A0506);
        }
        Instant now = this.clock.instant();
        Org org = new Org(UuidV7.generate().toString(), name, now);
        try {
            this.orgRepository.save(org);
        } catch (DataIntegrityViolationException ex) {
            // 先查后插的窗口内并发撞名：唯一约束兜底，统一以业务异常对外
            throw new JauthException(JauthErrorCode.A0506);
        }
        this.orgRepository.saveMember(new OrgMember(org.id(), creatorUserId, OrgRole.OWNER, now));
        this.auditPublisher.publish(
                AuditEvent.of(AuditEventType.ORG_CREATED, creatorUserId, "org", org.id(), "name=" + name));
        return org;
    }

    /**
     * 查用户归属的全部 org（org + role 列表）。
     *
     * @param userId 用户 id
     * @return 归属条目列表（无归属为空列表）
     */
    public List<OrgMembership> findByUser(String userId) {
        Assert.hasText(userId, "userId cannot be empty");
        return this.orgRepository.findMembershipsByUser(userId);
    }

    /**
     * OWNER 门：安装审批/驳回/撤销的前置校验依赖（审批权在 OWNER，SPEC §3）。
     *
     * @param orgId org id
     * @param userId 用户 id
     * @return 该用户是否此 org 的 OWNER
     */
    public boolean isOwner(String orgId, String userId) {
        Assert.hasText(orgId, "orgId cannot be empty");
        Assert.hasText(userId, "userId cannot be empty");
        OrgMember member = this.orgRepository.findMember(orgId, userId);
        return member != null && member.role() == OrgRole.OWNER;
    }
}
