package io.github.oatelauser.jauth.core.audit;

/**
 * 审计事件类型词表（票 07：词表归代码枚举所有，DB 列不加 CHECK）。
 *
 * <p>v1.0 覆盖：登录（成功/失败）、令牌（签发/刷新/撤销）、consent 接受；v1.1 追加审批事件
 * （jauth_installation），届时在此扩枚举值即可。
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
    CONSENT_ACCEPTED("consent.accepted");

    private final String wireName;

    AuditEventType(String wireName) {
        this.wireName = wireName;
    }

    /** 落库/对外标识（点分小写，与表 event_type 列一致）。 */
    public String wireName() {
        return this.wireName;
    }
}
