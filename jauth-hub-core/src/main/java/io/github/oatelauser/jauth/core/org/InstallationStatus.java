package io.github.oatelauser.jauth.core.org;

/**
 * 安装状态机词表（票 07：词表归代码枚举，DB 列不加 CHECK，列值即枚举名）。
 *
 * <p>合法转移（流向 A 两步制，2026-09-30 拍板）：
 * <ul>
 * <li>PENDING → APPROVED / REJECTED：org OWNER 审批（approve 勾 ceiling_scopes 生效）
 * <li>APPROVED → REVOKED：org OWNER 撤销
 * <li>REJECTED / REVOKED → PENDING：允许重新发起（原行重置，不另起新行）
 * </ul>
 * 其余转移皆非法，由 {@link InstallationService} 在服务层强制。
 *
 * @author oatelauser
 */
public enum InstallationStatus {

    /** 待审批：任何登录用户发起的安装请求初始态。 */
    PENDING,

    /** 已批准：ceiling_scopes 生效，发行 scope 封顶（运行时取交在 B9 接线）。 */
    APPROVED,

    /** 已驳回：可重新发起。 */
    REJECTED,

    /** 已撤销：曾 APPROVED 后被 OWNER 收回，可重新发起。 */
    REVOKED
}
