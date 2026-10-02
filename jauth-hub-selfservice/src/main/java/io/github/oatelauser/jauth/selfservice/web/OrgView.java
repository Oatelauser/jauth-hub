package io.github.oatelauser.jauth.selfservice.web;

/**
 * org 族页面态的 org 投影（v1.5 B1b）：org-apps / org-installations / org-members 三个 JSON 状态面共用的
 * org 标识段。字段名与 {@link io.github.oatelauser.jauth.core.org.OrgMembership} 的 orgId/orgName 对齐——
 * 前端在 my-orgs 列表行与 org 详情页头之间消费同一形状；不携带 createdAt 等域内字段（状态面无消费方）。
 *
 * @param orgId org id
 * @param orgName org 名
 * @author oatelauser
 */
public record OrgView(String orgId, String orgName) {}
