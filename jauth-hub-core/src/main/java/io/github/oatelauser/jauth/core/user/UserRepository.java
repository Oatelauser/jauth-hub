package io.github.oatelauser.jauth.core.user;

import java.time.Instant;
import java.util.List;
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
     * 更新角色（SUPERADMIN/USER）。
     *
     * @param id 用户 id
     * @param role 新角色
     */
    void updateRole(String id, String role);

    /**
     * 更新展示名（档案自助维护，B12）。
     *
     * @param id 用户 id
     * @param displayName 新展示名，可为空（UI 回退 username）
     */
    void updateDisplayName(String id, @Nullable String displayName);

    /**
     * 全量列表（app 用户管理页，B12）：单部署用户量形态不设过滤/分页。
     *
     * @return 全部用户，按登录名升序
     */
    List<JauthUser> findAll();

    /**
     * 用户总数（v1.3 D5 老账⑦ 用户列表分页的 total 源）。
     *
     * @return 用户行数
     */
    long countAll();

    /**
     * 分页取用户（username ASC 定序与 findAll 一致；v1.3 D5 老账⑦）。
     *
     * @param offset 起始偏移（0 起）
     * @param limit 页大小
     * @return 当前页用户行
     */
    List<JauthUser> findPage(int offset, int limit);

    /**
     * 更新最近强认证时间（sudo 位）。
     *
     * @param id 用户 id
     * @param strongAuthAt 强认证时间
     */
    void updateStrongAuthAt(String id, @Nullable Instant strongAuthAt);
}
