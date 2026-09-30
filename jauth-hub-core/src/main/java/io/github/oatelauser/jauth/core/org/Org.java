package io.github.oatelauser.jauth.core.org;

import java.time.Instant;

/**
 * 组织实体（表 jauth_org）。
 *
 * <p>org 是权限边界（SPEC §3）：安装、客户端归属、成员角色都以 org 为单位；创建走自助（任何登录用户可建，
 * 创建者自动成为 OWNER），本批仅域服务，无页面无端点（"我的组织"页是 B11）。
 *
 * @param id 主键，UUID v7 字符串
 * @param name 组织名，全局唯一（表有唯一约束）
 * @param createdAt 创建时间
 * @author oatelauser
 */
public record Org(String id, String name, Instant createdAt) {}
