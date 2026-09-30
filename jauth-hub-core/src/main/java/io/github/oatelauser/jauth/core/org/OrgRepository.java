package io.github.oatelauser.jauth.core.org;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 组织仓储（memory/jdbc 双实现共用契约，按 {@code jauth-hub.storage} 条件注册，测试以同一套抽象契约覆盖两实现）。
 *
 * <p>实现命名取舍同 {@code UserRepository}：{@code JdbcOrgRepository}/{@code InMemoryOrgRepository} 用模式前缀
 * 而非 Impl 后缀——两实现并存，前缀即其区分语义。
 *
 * @author oatelauser
 */
public interface OrgRepository {

    /**
     * 按主键查找。
     *
     * @param id org id
     * @return org，不存在返回 null
     */
    @Nullable
    Org findById(String id);

    /**
     * 按组织名查找（重名预检用）。
     *
     * @param name 组织名（全局唯一）
     * @return org，不存在返回 null
     */
    @Nullable
    Org findByName(String name);

    /**
     * 新增 org。重名由唯一约束拒绝（jdbc 抛 {@code DataIntegrityViolationException}，服务层先查后插 +
     * 约束兜底）。
     *
     * @param org 完整 org 实体
     */
    void save(Org org);

    /**
     * 新增成员关系（创建者 OWNER 或后续成员写入）。
     *
     * @param member 成员实体
     */
    void saveMember(OrgMember member);

    /**
     * 查单个成员关系（OWNER 门的数据源）。
     *
     * @param orgId org id
     * @param userId 用户 id
     * @return 成员关系，非成员返回 null
     */
    @Nullable
    OrgMember findMember(String orgId, String userId);

    /**
     * 查用户归属的全部 org（含角色与 org 名）。
     *
     * @param userId 用户 id
     * @return 归属条目列表（无归属为空列表）
     */
    List<OrgMembership> findMembershipsByUser(String userId);
}
