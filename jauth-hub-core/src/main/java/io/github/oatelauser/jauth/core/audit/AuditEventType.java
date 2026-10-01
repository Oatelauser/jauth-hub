package io.github.oatelauser.jauth.core.audit;

/**
 * 审计事件类型词表（票 07：词表归代码枚举所有，DB 列不加 CHECK）。
 *
 * <p>v1.0 覆盖：登录（成功/失败）、令牌（签发/刷新/撤销）、consent 接受；v1.1（B8）追加 org/安装域：
 * org 创建与安装两步制全生命周期（发起/批准/驳回/撤销），由 OrgService/InstallationService 服务路径发布；
 * v1.2（C1）追加 Passkey 凭据生命周期（注册/移除），挂在凭据仓储 save/delete 路径（框架过滤器在 HTTP 层，
 * 仓储包装是 jauth 侧唯一可控挂点）。
 *
 * @author oatelauser
 */
public enum AuditEventType {

    /** 登录成功（表单登录，Spring Security AuthenticationSuccessEvent 接线）。 */
    LOGIN_SUCCESS("login.success"),

    /** 登录失败（AbstractAuthenticationFailureEvent 接线，detail 记失败原因类名）。 */
    LOGIN_FAILED("login.failed"),

    /** 令牌首签（授权码换取 access token，save 路径）。 */
    TOKEN_ISSUED("token.issued"),

    /** 令牌刷新（RTR 轮转后的再签发，save 路径）。 */
    TOKEN_REFRESHED("token.refreshed"),

    /** 令牌撤销（/revoke 端点与看板一键 revoke，remove 路径）。 */
    TOKEN_REVOKED("token.revoked"),

    /** consent 接受（consent 服务 save 路径）。 */
    CONSENT_ACCEPTED("consent.accepted"),

    /** org 自助创建（创建者自动 OWNER，OrgService.create 路径）。 */
    ORG_CREATED("org.created"),

    /** 安装请求发起（任何登录用户，InstallationService.request 路径）。 */
    INSTALL_REQUESTED("installation.requested"),

    /** 安装批准（OWNER 勾 ceiling_scopes，InstallationService.approve 路径）。 */
    INSTALL_APPROVED("installation.approved"),

    /** 安装驳回（OWNER，InstallationService.reject 路径）。 */
    INSTALL_REJECTED("installation.rejected"),

    /** 安装撤销（OWNER 对 APPROVED 行收回，InstallationService.revoke 路径）。 */
    INSTALL_REVOKED("installation.revoked"),

    /** Passkey 凭据注册（框架 WebAuthnRegistrationFilter → 仓储 save 的插入路径，v1.2）。 */
    PASSKEY_REGISTERED("passkey.registered"),

    /** Passkey 凭据移除（DELETE /webauthn/register/{id} → 仓储 delete 路径，v1.2）。 */
    PASSKEY_REMOVED("passkey.removed");

    private final String wireName;

    AuditEventType(String wireName) {
        this.wireName = wireName;
    }

    /** 落库/对外标识（点分小写，与表 event_type 列一致）。 */
    public String wireName() {
        return this.wireName;
    }
}
