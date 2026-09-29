package io.github.oatelauser.jauth.core.user;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * 用户仓储（memory/jdbc 双实现共用契约，测试以同一套抽象契约覆盖两实现）。
 *
 * <p>实现命名取舍：{@code JdbcUserRepository}/{@code InMemoryUserRepository} 用模式前缀而非 p3c 的 Impl 后缀——
 * 两实现并存且按 {@code jauth-hub.storage} 条件注册（SPEC §1），前缀即其区分语义。
 *
 * @author oatelauser
 */
public interface UserRepository {

    /**
     * 按主键查找。
     *
     * @param id 用户 id
     * @return 用户，不存在返回 null
     */
    @Nullable
    JauthUser findById(String id);

    /**
     * 按登录名查找。
     *
     * @param username 登录名（全局唯一）
     * @return 用户，不存在返回 null
     */
    @Nullable
    JauthUser findByUsername(String username);

    /**
     * 新增用户。id 由调用方生成（UUID v7）；登录名重复由唯一约束拒绝。
     *
     * @param user 完整用户实体
     */
    void save(JauthUser user);

    /**
     * 更新密码哈希。
     *
     * @param id 用户 id
     * @param passwordHash 新的已编码密码
     */
    void updatePasswordHash(String id, String passwordHash);

    /**
     * 更新状态（ACTIVE/DISABLED）。
     *
     * @param id 用户 id
     * @param status 新状态
     */
    void updateStatus(String id, String status);

    /**
     * 更新最近强认证时间（sudo 位）。
     *
     * @param id 用户 id
     * @param strongAuthAt 强认证时间
     */
    void updateStrongAuthAt(String id, @Nullable Instant strongAuthAt);
}
