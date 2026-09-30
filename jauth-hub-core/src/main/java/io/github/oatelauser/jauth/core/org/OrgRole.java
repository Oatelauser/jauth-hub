package io.github.oatelauser.jauth.core.org;

/**
 * org 成员角色词表（票 07：词表归代码枚举，DB 列不加 CHECK，列值即枚举名）。
 *
 * <p>SPEC §3：org 是权限边界，安装审批权在 OWNER——MEMBER 只是归属关系，无审批/撤销权。
 *
 * @author oatelauser
 */
public enum OrgRole {

    /** 组织所有者：安装审批（approve/reject）与撤销（revoke）权的持有者。 */
    OWNER,

    /** 普通成员：可发起安装请求，不持审批权。 */
    MEMBER
}
