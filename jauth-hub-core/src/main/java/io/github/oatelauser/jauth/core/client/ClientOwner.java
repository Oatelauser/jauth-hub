package io.github.oatelauser.jauth.core.client;

import org.springframework.util.Assert;

/**
 * 客户端归属（oauth2_registered_client.owner_user_id / owner_org_id 两列的领域视图）。
 *
 * <p>语义（SPEC §3）：个人应用与组织应用并存，至多一非空——皆空表示平台内置客户端 （如内省使用的机密客户端），皆设无意义。DB 层由 CHECK 约束兜底同样的规则。
 *
 * @param userId 归属用户 id，组织归属时为 null
 * @param orgId 归属组织 id，个人归属时为 null
 * @author oatelauser
 */
public record ClientOwner(String userId, String orgId) {

    public ClientOwner {
        Assert.isTrue(userId == null || orgId == null, "owner_user_id 与 owner_org_id 至多一非空：个人/组织二选一，皆空表示平台内置");
    }

    /**
     * 平台内置客户端（无归属）。
     *
     * @return 空 owner
     */
    public static ClientOwner platform() {
        return new ClientOwner(null, null);
    }

    /**
     * 个人应用归属。
     *
     * @param userId 归属用户 id
     * @return 个人 owner
     */
    public static ClientOwner ofUser(String userId) {
        Assert.hasText(userId, "userId cannot be empty");
        return new ClientOwner(userId, null);
    }

    /**
     * 组织应用归属。
     *
     * @param orgId 归属组织 id
     * @return 组织 owner
     */
    public static ClientOwner ofOrg(String orgId) {
        Assert.hasText(orgId, "orgId cannot be empty");
        return new ClientOwner(null, orgId);
    }
}
