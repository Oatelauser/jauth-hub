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
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.util.Assert;

/**
 * org 域服务：自助创建（任何登录用户可建，创建者自动成为 OWNER，2026-09-30 拍板）+ 查询 + OWNER 门。
 *
 * <p>创建的 org 与成员两写包<b>可选事务</b>（B10 滑账①收口）：构造器注入 {@link TransactionOperations}（spring-tx
 * 接口，TransactionTemplate 即其实现），有则 org+member 两写同成同败——成员写失败不再留下无主 org；无（null，
 * memory 仓储或容器无事务管理器）直通两条语句，语义同 B8（内存仓储无回滚概念）。审计发布在事务块之后：发布
 * 失败只缺事件行，不回滚已成立的领域写。
 *
 * <p>成员管理面（v1.3 D3）：OWNER 的列表/添加/移除/角色翻转，OWNER 门在服务层单点；org 删除与邀请/申请流
 * 仍不做（v2+ 记档）。
 *
 * @author oatelauser
 */
public class OrgService {

    private final OrgRepository orgRepository;

    private final AuditEventPublisher auditPublisher;

    private final Clock clock;

    private final @Nullable TransactionOperations transactionOperations;

    /**
     * EI_EXPOSE_REP2 定向豁免：仓储/服务是容器单例门面（Spring 注入通行形态，构造后无可变面暴露）。
     */
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(value = "EI_EXPOSE_REP2")
    public OrgService(
            OrgRepository orgRepository,
            AuditEventPublisher auditPublisher,
            Clock clock,
            @Nullable TransactionOperations transactionOperations) {
        this.orgRepository = orgRepository;
        this.auditPublisher = auditPublisher;
        this.clock = clock;
        this.transactionOperations = transactionOperations;
    }

    /**
     * 自助创建 org：创建者自动成为 OWNER 成员，发布 org.created 审计。
     *
     * <p>重名拒绝走先查后插 + 唯一约束兜底（并发窗口内撞约束同样翻译为业务异常）。org+member 两写在
     * {@link TransactionOperations} 在场时同成同败（类注释：成员写失败回滚 org 落库）。
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
        Runnable writes = () -> {
            try {
                this.orgRepository.save(org);
            } catch (DataIntegrityViolationException ex) {
                // 先查后插的窗口内并发撞名：唯一约束兜底，统一以业务异常对外（事务内同样触发回滚）
                throw new JauthException(JauthErrorCode.A0506);
            }
            this.orgRepository.saveMember(new OrgMember(org.id(), creatorUserId, OrgRole.OWNER, now));
        };
        if (this.transactionOperations != null) {
            this.transactionOperations.executeWithoutResult(status -> writes.run());
        } else {
            writes.run();
        }
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

    /**
     * 成员列表（v1.3 D3，OWNER 面）：OWNER 门在服务层单点（列表/增/删/改角色共径）。
     *
     * @param orgId org id
     * @param actingUserId 操作者（须 OWNER，否则 A0508）
     * @return 成员行（按加入时间序）
     */
    public List<OrgMember> listMembers(String orgId, String actingUserId) {
        requireOwner(orgId, actingUserId);
        return this.orgRepository.findMembersByOrg(orgId);
    }

    /**
     * 添加成员（v1.3 D3）：OWNER 直接添加（输入用户名解析为用户 id 由控制器完成——用户名→id 的池查询属
     * 用户域）；已是的成员拒绝（A0516，幂等加不提供——角色变更面独立）；新成员恒 MEMBER。
     *
     * @param orgId org id
     * @param actingUserId 操作者（须 OWNER）
     * @param newMemberUserId 新成员用户 id
     * @return 落库后的成员行
     */
    public OrgMember addMember(String orgId, String actingUserId, String newMemberUserId) {
        requireOwner(orgId, actingUserId);
        Assert.hasText(newMemberUserId, "newMemberUserId cannot be empty");
        if (this.orgRepository.findMember(orgId, newMemberUserId) != null) {
            throw new JauthException(JauthErrorCode.A0516);
        }
        OrgMember member = new OrgMember(orgId, newMemberUserId, OrgRole.MEMBER, this.clock.instant());
        this.orgRepository.saveMember(member);
        this.auditPublisher.publish(
                AuditEvent.of(AuditEventType.MEMBER_ADDED, actingUserId, "org", orgId, "member=" + newMemberUserId));
        return member;
    }

    /**
     * 移除成员（v1.3 D3，销毁性）：自操作拒绝（A0518）——操作者自身的 OWNER 因此恒不可被本面移除，
     * 最后一个 OWNER 结构性锁死（AdminUsersController 的 A0513 同义防线）。不级联清该成员的授权/安装
     * （用户侧清剿面在 D1，按主体全量；org 侧数据随其失效路径各归其主）。
     *
     * @param orgId org id
     * @param actingUserId 操作者（须 OWNER）
     * @param targetUserId 目标成员
     */
    public void removeMember(String orgId, String actingUserId, String targetUserId) {
        requireOwner(orgId, actingUserId);
        requireMember(orgId, targetUserId);
        if (targetUserId.equals(actingUserId)) {
            throw new JauthException(JauthErrorCode.A0518);
        }
        this.orgRepository.deleteMember(orgId, targetUserId);
        this.auditPublisher.publish(
                AuditEvent.of(AuditEventType.MEMBER_REMOVED, actingUserId, "org", orgId, "member=" + targetUserId));
    }

    /**
     * 成员角色变更（v1.3 D3，销毁性降级面）：OWNER↔MEMBER 翻转；自操作拒绝（A0518，同移除防线）。
     *
     * @param orgId org id
     * @param actingUserId 操作者（须 OWNER）
     * @param targetUserId 目标成员
     * @param newRole 新角色
     * @return 更新后的成员行
     */
    public OrgMember changeMemberRole(String orgId, String actingUserId, String targetUserId, OrgRole newRole) {
        requireOwner(orgId, actingUserId);
        OrgMember existing = requireMember(orgId, targetUserId);
        if (targetUserId.equals(actingUserId)) {
            throw new JauthException(JauthErrorCode.A0518);
        }
        this.orgRepository.updateMemberRole(orgId, targetUserId, newRole);
        OrgMember updated = new OrgMember(orgId, targetUserId, newRole, existing.createdAt());
        this.auditPublisher.publish(AuditEvent.of(
                AuditEventType.MEMBER_ROLE_CHANGED,
                actingUserId,
                "org",
                orgId,
                "member=" + targetUserId + " role=" + newRole.name()));
        return updated;
    }

    /** OWNER 门（成员管理面共径；org 不存在与非 OWNER 同译 A0508——不泄露 org 存在性）。 */
    private void requireOwner(String orgId, String actingUserId) {
        Assert.hasText(orgId, "orgId cannot be empty");
        Assert.hasText(actingUserId, "actingUserId cannot be empty");
        if (!isOwner(orgId, actingUserId)) {
            throw new JauthException(JauthErrorCode.A0508);
        }
    }

    private OrgMember requireMember(String orgId, String userId) {
        Assert.hasText(userId, "userId cannot be empty");
        OrgMember member = this.orgRepository.findMember(orgId, userId);
        if (member == null) {
            throw new JauthException(JauthErrorCode.A0517);
        }
        return member;
    }
}
