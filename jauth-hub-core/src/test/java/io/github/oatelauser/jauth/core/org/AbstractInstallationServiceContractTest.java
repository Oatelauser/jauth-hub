package io.github.oatelauser.jauth.core.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.oatelauser.jauth.core.audit.AuditEventType;
import io.github.oatelauser.jauth.core.response.JauthErrorCode;
import io.github.oatelauser.jauth.core.response.JauthException;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 安装域契约测试（抽象基类，继承 org 契约基类复用装配）：状态机全转移 + 非法转移 + OWNER 门 + ceiling 越界 +
 * 重发语义 + 审计发布，同一套断言跑 InMemory 与 JDBC 两实现。
 *
 * @author oatelauser
 */
abstract class AbstractInstallationServiceContractTest extends AbstractOrgServiceContractTest {

    /** 公共前置：OWNER 持有的 org + MEMBER 发起的 PENDING 请求（requestedScopes = openid + profile）。 */
    protected Installation pendingRequest() {
        Org org = fixture().orgService().create("acme", OWNER_ID);
        return fixture().installationService().request(CLIENT_ID, org.id(), Set.of("openid", "profile"), MEMBER_ID);
    }

    @Test
    void requestPersistsPendingRowAndAudits() {
        Installation installation = pendingRequest();

        assertThat(installation.status()).isEqualTo(InstallationStatus.PENDING);
        assertThat(installation.registeredClientId()).isEqualTo(CLIENT_ID);
        assertThat(installation.requestedBy()).isEqualTo(MEMBER_ID);
        assertThat(installation.requestedScopes()).containsExactlyInAnyOrder("openid", "profile");
        assertThat(installation.ceilingScopes()).isEmpty();
        assertThat(installation.approvedBy()).isNull();
        assertThat(installation.approvedAt()).isNull();
        assertThat(fixture().installationRepository().findById(installation.id()))
                .isEqualTo(installation);
        assertThat(eventsOfType(AuditEventType.INSTALL_REQUESTED))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.actorUserId()).isEqualTo(MEMBER_ID);
                    assertThat(event.targetType()).isEqualTo("installation");
                    assertThat(event.targetId()).isEqualTo(installation.id());
                });
    }

    @Test
    void requestRejectsUnknownClientOrOrg() {
        Org org = fixture().orgService().create("acme", OWNER_ID);
        InstallationService service = fixture().installationService();

        assertThatThrownBy(() -> service.request("no-such-client", org.id(), Set.of("openid"), OWNER_ID))
                .isInstanceOfSatisfying(
                        JauthException.class, ex -> assertThat(ex.getCode()).isEqualTo(JauthErrorCode.B0502.getCode()));
        assertThatThrownBy(() ->
                        service.request(CLIENT_ID, "00000000-0000-7000-8000-0000000000ff", Set.of("openid"), OWNER_ID))
                .isInstanceOfSatisfying(
                        JauthException.class, ex -> assertThat(ex.getCode()).isEqualTo(JauthErrorCode.B0502.getCode()));
    }

    @Test
    void requestRejectedWhilePendingOrApproved() {
        Installation installation = pendingRequest();
        InstallationService service = fixture().installationService();

        assertThatThrownBy(() -> service.request(CLIENT_ID, installation.orgId(), Set.of("email"), OUTSIDER_ID))
                .isInstanceOfSatisfying(
                        JauthException.class, ex -> assertThat(ex.getCode()).isEqualTo(JauthErrorCode.A0506.getCode()));

        service.approve(installation.id(), OWNER_ID, Set.of("openid"));
        assertThatThrownBy(() -> service.request(CLIENT_ID, installation.orgId(), Set.of("email"), OUTSIDER_ID))
                .isInstanceOfSatisfying(
                        JauthException.class, ex -> assertThat(ex.getCode()).isEqualTo(JauthErrorCode.A0506.getCode()));
    }

    @Test
    void approveStoresCeilingApproverAndAudits() {
        Installation installation = pendingRequest();

        Installation approved = fixture().installationService().approve(installation.id(), OWNER_ID, Set.of("openid"));

        assertThat(approved.status()).isEqualTo(InstallationStatus.APPROVED);
        assertThat(approved.ceilingScopes()).containsExactly("openid");
        assertThat(approved.approvedBy()).isEqualTo(OWNER_ID);
        assertThat(approved.approvedAt()).isEqualTo(FIXED_NOW);
        assertThat(approved.requestedScopes()).containsExactlyInAnyOrder("openid", "profile");
        assertThat(approved.createdAt()).isEqualTo(installation.createdAt());
        assertThat(fixture().installationRepository().findById(approved.id())).isEqualTo(approved);
        assertThat(eventsOfType(AuditEventType.INSTALL_APPROVED))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.actorUserId()).isEqualTo(OWNER_ID);
                    assertThat(event.targetType()).isEqualTo("installation");
                    assertThat(event.targetId()).isEqualTo(approved.id());
                });
    }

    @Test
    void nonOwnerCannotApproveRejectOrRevoke() {
        Installation installation = pendingRequest();
        InstallationService service = fixture().installationService();

        assertThatThrownBy(() -> service.approve(installation.id(), MEMBER_ID, Set.of("openid")))
                .isInstanceOfSatisfying(
                        JauthException.class, ex -> assertThat(ex.getCode()).isEqualTo(JauthErrorCode.A0508.getCode()));
        assertThatThrownBy(() -> service.approve(installation.id(), OUTSIDER_ID, Set.of("openid")))
                .isInstanceOfSatisfying(
                        JauthException.class, ex -> assertThat(ex.getCode()).isEqualTo(JauthErrorCode.A0508.getCode()));
        assertThatThrownBy(() -> service.reject(installation.id(), MEMBER_ID))
                .isInstanceOfSatisfying(
                        JauthException.class, ex -> assertThat(ex.getCode()).isEqualTo(JauthErrorCode.A0508.getCode()));

        service.approve(installation.id(), OWNER_ID, Set.of("openid"));
        assertThatThrownBy(() -> service.revoke(installation.id(), MEMBER_ID))
                .isInstanceOfSatisfying(
                        JauthException.class, ex -> assertThat(ex.getCode()).isEqualTo(JauthErrorCode.A0508.getCode()));
    }

    @Test
    void approveRejectsCeilingBeyondRequestedAndKeepsRowPending() {
        Installation installation = pendingRequest();

        assertThatThrownBy(() -> fixture().installationService().approve(installation.id(), OWNER_ID, Set.of("admin")))
                .isInstanceOfSatisfying(
                        JauthException.class, ex -> assertThat(ex.getCode()).isEqualTo(JauthErrorCode.A0502.getCode()));
        assertThat(fixture()
                        .installationRepository()
                        .findById(installation.id())
                        .status())
                .isEqualTo(InstallationStatus.PENDING);
    }

    @Test
    void approveAllowsCeilingEqualToFullRequest() {
        Installation installation = pendingRequest();

        Installation approved =
                fixture().installationService().approve(installation.id(), OWNER_ID, Set.of("openid", "profile"));

        assertThat(approved.ceilingScopes()).containsExactlyInAnyOrder("openid", "profile");
    }

    @Test
    void approvedRowCannotBeApprovedRejectedOrRevokedAgain() {
        Installation installation = pendingRequest();
        InstallationService service = fixture().installationService();
        service.approve(installation.id(), OWNER_ID, Set.of("openid"));

        assertThatThrownBy(() -> service.approve(installation.id(), OWNER_ID, Set.of("openid")))
                .isInstanceOfSatisfying(
                        JauthException.class, ex -> assertThat(ex.getCode()).isEqualTo(JauthErrorCode.A0507.getCode()));
        assertThatThrownBy(() -> service.reject(installation.id(), OWNER_ID))
                .isInstanceOfSatisfying(
                        JauthException.class, ex -> assertThat(ex.getCode()).isEqualTo(JauthErrorCode.A0507.getCode()));

        service.revoke(installation.id(), OWNER_ID);
        assertThatThrownBy(() -> service.approve(installation.id(), OWNER_ID, Set.of("openid")))
                .isInstanceOfSatisfying(
                        JauthException.class, ex -> assertThat(ex.getCode()).isEqualTo(JauthErrorCode.A0507.getCode()));
        assertThatThrownBy(() -> service.reject(installation.id(), OWNER_ID))
                .isInstanceOfSatisfying(
                        JauthException.class, ex -> assertThat(ex.getCode()).isEqualTo(JauthErrorCode.A0507.getCode()));
        assertThatThrownBy(() -> service.revoke(installation.id(), OWNER_ID))
                .isInstanceOfSatisfying(
                        JauthException.class, ex -> assertThat(ex.getCode()).isEqualTo(JauthErrorCode.A0507.getCode()));
    }

    @Test
    void rejectedRowCannotBeApprovedRejectedOrRevoked() {
        Installation installation = pendingRequest();
        InstallationService service = fixture().installationService();
        service.reject(installation.id(), OWNER_ID);

        assertThatThrownBy(() -> service.approve(installation.id(), OWNER_ID, Set.of("openid")))
                .isInstanceOfSatisfying(
                        JauthException.class, ex -> assertThat(ex.getCode()).isEqualTo(JauthErrorCode.A0507.getCode()));
        assertThatThrownBy(() -> service.reject(installation.id(), OWNER_ID))
                .isInstanceOfSatisfying(
                        JauthException.class, ex -> assertThat(ex.getCode()).isEqualTo(JauthErrorCode.A0507.getCode()));
        assertThatThrownBy(() -> service.revoke(installation.id(), OWNER_ID))
                .isInstanceOfSatisfying(
                        JauthException.class, ex -> assertThat(ex.getCode()).isEqualTo(JauthErrorCode.A0507.getCode()));
    }

    @Test
    void pendingRowCannotBeRevoked() {
        Installation installation = pendingRequest();

        assertThatThrownBy(() -> fixture().installationService().revoke(installation.id(), OWNER_ID))
                .isInstanceOfSatisfying(
                        JauthException.class, ex -> assertThat(ex.getCode()).isEqualTo(JauthErrorCode.A0507.getCode()));
    }

    @Test
    void rejectThenResendResetsSameRow() {
        Installation installation = pendingRequest();
        InstallationService service = fixture().installationService();

        Installation rejected = service.reject(installation.id(), OWNER_ID);
        assertThat(rejected.status()).isEqualTo(InstallationStatus.REJECTED);
        assertThat(eventsOfType(AuditEventType.INSTALL_REJECTED))
                .singleElement()
                .satisfies(event -> assertThat(event.actorUserId()).isEqualTo(OWNER_ID));

        Installation resent = service.request(CLIENT_ID, installation.orgId(), Set.of("email"), OUTSIDER_ID);

        // 重发 = 原行重置：同 id、created_at 不动，请求侧更新、审批侧与旧 ceiling 清空
        assertThat(resent.id()).isEqualTo(installation.id());
        assertThat(resent.status()).isEqualTo(InstallationStatus.PENDING);
        assertThat(resent.requestedBy()).isEqualTo(OUTSIDER_ID);
        assertThat(resent.requestedScopes()).containsExactly("email");
        assertThat(resent.ceilingScopes()).isEmpty();
        assertThat(resent.approvedBy()).isNull();
        assertThat(resent.approvedAt()).isNull();
        assertThat(resent.createdAt()).isEqualTo(installation.createdAt());
        assertThat(fixture().installationRepository().findById(resent.id())).isEqualTo(resent);
    }

    @Test
    void revokeKeepsApprovalHistoryAndAllowsResend() {
        Installation installation = pendingRequest();
        InstallationService service = fixture().installationService();
        service.approve(installation.id(), OWNER_ID, Set.of("openid"));

        Installation revoked = service.revoke(installation.id(), OWNER_ID);

        // 撤销保留审批痕迹：收回的是授权关系，不是审批历史
        assertThat(revoked.status()).isEqualTo(InstallationStatus.REVOKED);
        assertThat(revoked.approvedBy()).isEqualTo(OWNER_ID);
        assertThat(revoked.approvedAt()).isEqualTo(FIXED_NOW);
        assertThat(revoked.ceilingScopes()).containsExactly("openid");
        assertThat(eventsOfType(AuditEventType.INSTALL_REVOKED))
                .singleElement()
                .satisfies(event -> assertThat(event.actorUserId()).isEqualTo(OWNER_ID));

        Installation resent = service.request(CLIENT_ID, installation.orgId(), Set.of("profile"), MEMBER_ID);

        assertThat(resent.id()).isEqualTo(installation.id());
        assertThat(resent.status()).isEqualTo(InstallationStatus.PENDING);
        assertThat(resent.ceilingScopes()).isEmpty();
        assertThat(resent.approvedBy()).isNull();
        assertThat(resent.approvedAt()).isNull();
    }

    @Test
    void unknownInstallationIdRejected() {
        String unknownId = "00000000-0000-7000-8000-0000000000ff";
        InstallationService service = fixture().installationService();

        assertThatThrownBy(() -> service.approve(unknownId, OWNER_ID, Set.of("openid")))
                .isInstanceOfSatisfying(
                        JauthException.class, ex -> assertThat(ex.getCode()).isEqualTo(JauthErrorCode.B0502.getCode()));
        assertThatThrownBy(() -> service.reject(unknownId, OWNER_ID))
                .isInstanceOfSatisfying(
                        JauthException.class, ex -> assertThat(ex.getCode()).isEqualTo(JauthErrorCode.B0502.getCode()));
        assertThatThrownBy(() -> service.revoke(unknownId, OWNER_ID))
                .isInstanceOfSatisfying(
                        JauthException.class, ex -> assertThat(ex.getCode()).isEqualTo(JauthErrorCode.B0502.getCode()));
    }

    @Test
    void emptyRequestedScopesRejected() {
        Org org = fixture().orgService().create("acme", OWNER_ID);

        assertThatThrownBy(() -> fixture().installationService().request(CLIENT_ID, org.id(), Set.of(), MEMBER_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
