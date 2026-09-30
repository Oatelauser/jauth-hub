package io.github.oatelauser.jauth.core.org;

/**
 * 用户视角的 org 归属条目（org + role）：{@link OrgService#findByUser} 的返回单元。
 *
 * <p>带 org 名是为了"我的组织"列表展示（B11）不必再按 id 逐个反查。
 *
 * @param orgId org id
 * @param orgName org 名
 * @param role 该用户在此 org 的角色
 * @author oatelauser
 */
public record OrgMembership(String orgId, String orgName, OrgRole role) {}
