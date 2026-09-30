package io.github.oatelauser.jauth.core.org;

import java.time.Instant;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.util.Assert;

/**
 * 安装实体（表 jauth_installation）：client × org 的授权关系，流向 A 两步制（2026-09-30 拍板）——
 * 任何登录用户发起请求（PENDING）→ org OWNER 审批时勾 ceiling_scopes 生效（APPROVED）或驳回（REJECTED）；
 * APPROVED 可撤销（REVOKED）。发行 scopes = 请求 ∩ consent ∩ ceiling 的运行时取交在 B9 接线，本实体只存
 * ceiling 与请求两侧。
 *
 * <p>scopes 集合语义序列化为 TEXT 空格分隔（惯例同 client/PAT 的 scopes 列）；紧凑构造做防御拷贝并拒 null，
 * 空 scopes 用空集而非 null 表示（V6 前存量行的 NULL 列读出即为空集）。
 *
 * @param id 主键，UUID v7 字符串
 * @param registeredClientId oauth2_registered_client.id（非对外 client_id）
 * @param orgId 所属 org id
 * @param status 状态机当前态
 * @param ceilingScopes 审批封顶 scopes（仅 APPROVED 及其后有值）
 * @param requestedBy 发起人（jauth_user.id）
 * @param requestedScopes 发起时请求的 scopes
 * @param approvedBy 审批人（jauth_user.id），未审批为 null
 * @param approvedAt 审批时间，未审批为 null
 * @param createdAt 首次发起时间（重发不重置）
 * @author oatelauser
 */
public record Installation(
        String id,
        String registeredClientId,
        String orgId,
        InstallationStatus status,
        Set<String> ceilingScopes,
        @Nullable String requestedBy,
        Set<String> requestedScopes,
        @Nullable String approvedBy,
        @Nullable Instant approvedAt,
        Instant createdAt) {

    public Installation {
        Assert.notNull(ceilingScopes, "ceilingScopes cannot be null");
        Assert.notNull(requestedScopes, "requestedScopes cannot be null");
        ceilingScopes = Set.copyOf(ceilingScopes);
        requestedScopes = Set.copyOf(requestedScopes);
    }
}
