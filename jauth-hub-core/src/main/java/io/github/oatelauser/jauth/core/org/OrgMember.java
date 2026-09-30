package io.github.oatelauser.jauth.core.org;

import java.time.Instant;

/**
 * 组织成员（表 jauth_org_member，联合主键 org_id + user_id）。
 *
 * <p>一个用户可属多 org（SPEC §3）；成员角色的增删管理面（邀请/移除）留 B11 有页面语义时再定，本批只写创建者
 * OWNER 一条路径。
 *
 * @param orgId 所属 org id
 * @param userId 成员用户 id
 * @param role 成员角色（OWNER/MEMBER）
 * @param createdAt 加入时间
 * @author oatelauser
 */
public record OrgMember(String orgId, String userId, OrgRole role, Instant createdAt) {}
