package io.github.oatelauser.jauth.core.org;

import io.github.oatelauser.jauth.core.audit.AuditEvent;
import io.github.oatelauser.jauth.core.audit.AuditEventPublisher;
import io.github.oatelauser.jauth.core.audit.AuditEventType;
import io.github.oatelauser.jauth.core.response.JauthErrorCode;
import io.github.oatelauser.jauth.core.response.JauthException;
import io.github.oatelauser.jauth.core.util.UuidV7;
import java.time.Clock;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.util.Assert;

/**
 * 安装域服务：流向 A 两步制状态机（2026-09-30 拍板）——request（任何登录用户可发起，控制点在 OWNER 审批，
 * 不在发起）→ OWNER approve（ceiling_scopes 生效）/ reject；APPROVED 可 revoke；REJECTED/REVOKED 允许重发。
 * 每个动作发布对应生命周期审计事件。
 *
 * <p>超管短路不做：那是 spring-plus 注解层语义，域服务不管。发行 scopes = 请求 ∩ consent ∩ ceiling 的运行时
 * 取交在 B9 接线，本服务只管状态机与 ceiling 落库。
 *
 * @author oatelauser
 */
public class InstallationService {

    private final InstallationRepository installationRepository;

    private final OrgRepository orgRepository;

    private final OrgService orgService;

    private final RegisteredClientRepository registeredClientRepository;

    private final AuditEventPublisher auditPublisher;

    private final Clock clock;

    /**
     * EI_EXPOSE_REP2 定向豁免：仓储/服务是容器单例门面（Spring 注入通行形态，构造后无可变面暴露）。
     */
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(value = "EI_EXPOSE_REP2")
    public InstallationService(
            InstallationRepository installationRepository,
            OrgRepository orgRepository,
            OrgService orgService,
            RegisteredClientRepository registeredClientRepository,
            AuditEventPublisher auditPublisher,
            Clock clock) {
        this.installationRepository = installationRepository;
        this.orgRepository = orgRepository;
        this.orgService = orgService;
        this.registeredClientRepository = registeredClientRepository;
        this.auditPublisher = auditPublisher;
        this.clock = clock;
    }

    /**
     * 发起安装请求：落 PENDING 行，发布 installation.requested 审计。
     *
     * <p>同 (client, org) 已有 PENDING 或 APPROVED 行 → 拒绝（唯一键活跃语义）；REJECTED/REVOKED → 允许重发，
     * 原行重置回全新请求态：status=PENDING、更新 requested_by/requested_scopes、清空 approved_by/approved_at 与
     * 旧 ceiling（旧审批痕迹不残留，created_at 不动）。
     *
     * @param registeredClientId oauth2_registered_client.id（非对外 client_id）
     * @param orgId 目标 org id
     * @param requestedScopes 请求的 scopes（非空）
     * @param requestedBy 发起人（任何登录用户，不要求 org 成员）
     * @return PENDING 安装行
     */
    public Installation request(
            String registeredClientId, String orgId, Set<String> requestedScopes, String requestedBy) {
        Assert.hasText(registeredClientId, "registeredClientId cannot be empty");
        Assert.hasText(orgId, "orgId cannot be empty");
        Assert.notEmpty(requestedScopes, "requestedScopes cannot be empty");
        Assert.hasText(requestedBy, "requestedBy cannot be empty");
        requireClientExists(registeredClientId);
        requireOrgExists(orgId);
        Installation existing = this.installationRepository.findByClientAndOrg(registeredClientId, orgId);
        Installation requested;
        if (existing == null) {
            requested = newRequest(registeredClientId, orgId, requestedScopes, requestedBy);
            try {
                this.installationRepository.save(requested);
            } catch (DataIntegrityViolationException ex) {
                // 先查后插窗口内并发撞 (client, org) 唯一键：DB 约束兜底统一翻译为业务冲突（同 OrgService.create）
                throw new JauthException(JauthErrorCode.A0506);
            }
        } else if (existing.status() == InstallationStatus.PENDING
                || existing.status() == InstallationStatus.APPROVED) {
            throw new JauthException(JauthErrorCode.A0506);
        } else {
            requested = resetToPending(existing, requestedScopes, requestedBy);
            this.installationRepository.update(requested);
        }
        this.auditPublisher.publish(AuditEvent.of(
                AuditEventType.INSTALL_REQUESTED,
                requestedBy,
                "installation",
                requested.id(),
                "clientId=" + registeredClientId + ", scopes=" + String.join(" ", requestedScopes)));
        return requested;
    }

    /**
     * OWNER 审批通过：PENDING → APPROVED，落 ceiling_scopes/approved_by/approved_at，发布
     * installation.approved 审计。
     *
     * @param id 安装 id
     * @param approverUserId 审批人（必须是该 org OWNER）
     * @param ceilingScopes 封顶 scopes（必须 ⊆ requestedScopes，越界即审批错）
     * @return APPROVED 安装行
     */
    public Installation approve(String id, String approverUserId, Set<String> ceilingScopes) {
        Assert.hasText(id, "id cannot be empty");
        Assert.hasText(approverUserId, "approverUserId cannot be empty");
        Assert.notNull(ceilingScopes, "ceilingScopes cannot be null");
        Installation installation = requireInstallation(id);
        requireOwner(installation.orgId(), approverUserId);
        requireState(installation, InstallationStatus.PENDING);
        if (!installation.requestedScopes().containsAll(ceilingScopes)) {
            // ceiling 超出请求范围 = 审批参数非法：封顶只能在请求的 scopes 内勾选
            throw new JauthException(JauthErrorCode.A0502);
        }
        Installation approved = new Installation(
                installation.id(),
                installation.registeredClientId(),
                installation.orgId(),
                InstallationStatus.APPROVED,
                ceilingScopes,
                installation.requestedBy(),
                installation.requestedScopes(),
                approverUserId,
                this.clock.instant(),
                installation.createdAt());
        this.installationRepository.update(approved);
        this.auditPublisher.publish(AuditEvent.of(
                AuditEventType.INSTALL_APPROVED,
                approverUserId,
                "installation",
                approved.id(),
                "scopes=" + String.join(" ", ceilingScopes)));
        return approved;
    }

    /**
     * OWNER 驳回：PENDING → REJECTED，发布 installation.rejected 审计。
     *
     * @param id 安装 id
     * @param approverUserId 审批人（必须是该 org OWNER）
     * @return REJECTED 安装行
     */
    public Installation reject(String id, String approverUserId) {
        Assert.hasText(id, "id cannot be empty");
        Assert.hasText(approverUserId, "approverUserId cannot be empty");
        Installation installation = requireInstallation(id);
        requireOwner(installation.orgId(), approverUserId);
        requireState(installation, InstallationStatus.PENDING);
        Installation rejected = withStatus(installation, InstallationStatus.REJECTED);
        this.installationRepository.update(rejected);
        this.auditPublisher.publish(
                AuditEvent.of(AuditEventType.INSTALL_REJECTED, approverUserId, "installation", rejected.id(), null));
        return rejected;
    }

    /**
     * OWNER 撤销：APPROVED → REVOKED，发布 installation.revoked 审计。
     *
     * <p>撤销保留 approved_by/approved_at：撤销的是授权关系，不是审批历史。
     *
     * @param id 安装 id
     * @param operatorUserId 操作者（必须是该 org OWNER）
     * @return REVOKED 安装行
     */
    public Installation revoke(String id, String operatorUserId) {
        Assert.hasText(id, "id cannot be empty");
        Assert.hasText(operatorUserId, "operatorUserId cannot be empty");
        Installation installation = requireInstallation(id);
        requireOwner(installation.orgId(), operatorUserId);
        requireState(installation, InstallationStatus.APPROVED);
        Installation revoked = withStatus(installation, InstallationStatus.REVOKED);
        this.installationRepository.update(revoked);
        this.auditPublisher.publish(
                AuditEvent.of(AuditEventType.INSTALL_REVOKED, operatorUserId, "installation", revoked.id(), null));
        return revoked;
    }

    private Installation newRequest(
            String registeredClientId, String orgId, Set<String> requestedScopes, String requestedBy) {
        return new Installation(
                UuidV7.generate().toString(),
                registeredClientId,
                orgId,
                InstallationStatus.PENDING,
                Set.of(),
                requestedBy,
                requestedScopes,
                null,
                null,
                this.clock.instant());
    }

    private static Installation resetToPending(Installation existing, Set<String> requestedScopes, String requestedBy) {
        return new Installation(
                existing.id(),
                existing.registeredClientId(),
                existing.orgId(),
                InstallationStatus.PENDING,
                Set.of(),
                requestedBy,
                requestedScopes,
                null,
                null,
                existing.createdAt());
    }

    private static Installation withStatus(Installation source, InstallationStatus status) {
        return new Installation(
                source.id(),
                source.registeredClientId(),
                source.orgId(),
                status,
                source.ceilingScopes(),
                source.requestedBy(),
                source.requestedScopes(),
                source.approvedBy(),
                source.approvedAt(),
                source.createdAt());
    }

    private void requireClientExists(String registeredClientId) {
        if (this.registeredClientRepository.findById(registeredClientId) == null) {
            throw new JauthException(JauthErrorCode.B0502);
        }
    }

    private void requireOrgExists(String orgId) {
        if (this.orgRepository.findById(orgId) == null) {
            throw new JauthException(JauthErrorCode.B0502);
        }
    }

    private Installation requireInstallation(String id) {
        Installation installation = this.installationRepository.findById(id);
        if (installation == null) {
            throw new JauthException(JauthErrorCode.B0502);
        }
        return installation;
    }

    private void requireOwner(String orgId, String userId) {
        if (!this.orgService.isOwner(orgId, userId)) {
            throw new JauthException(JauthErrorCode.A0508);
        }
    }

    private static void requireState(Installation installation, InstallationStatus expected) {
        if (installation.status() != expected) {
            throw new JauthException(JauthErrorCode.A0507);
        }
    }
}
