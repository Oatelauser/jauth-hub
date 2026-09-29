package io.github.oatelauser.jauth.core.user;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * 用户实体（表 jauth_user）。
 *
 * <p>词表常量即列值词汇（票 07 决议 DB 层不加 CHECK，词表归代码所有）：{@code role} ∈ {@link #ROLE_SUPERADMIN}/{@link
 * #ROLE_USER}，{@code status} ∈ {@link #STATUS_ACTIVE}/{@link #STATUS_DISABLED}。
 * 密码编码（bcrypt/Argon2）不属存储域：本实体只保存已编码的 {@code passwordHash}，编码由 service 层完成。
 *
 * @param id 主键，UUID v7 字符串
 * @param username 登录名，全局唯一
 * @param passwordHash 已编码密码
 * @param displayName 展示名，可为空（UI 回退 username）
 * @param email 预留列（v1 不启用邮箱流）
 * @param role 角色：SUPERADMIN 或 USER
 * @param status 状态：ACTIVE 或 DISABLED
 * @param strongAuthAt 最近强认证时间（sudo 位，v1.2 依赖），NULL = 从未强认证
 * @param createdAt 创建时间
 * @author oatelauser
 */
public record JauthUser(
        String id,
        String username,
        String passwordHash,
        @Nullable String displayName,
        @Nullable String email,
        String role,
        String status,
        @Nullable Instant strongAuthAt,
        Instant createdAt) {

    public static final String ROLE_SUPERADMIN = "SUPERADMIN";

    public static final String ROLE_USER = "USER";

    public static final String STATUS_ACTIVE = "ACTIVE";

    public static final String STATUS_DISABLED = "DISABLED";
}
