package io.github.oatelauser.jauth.core.org;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 安装仓储（memory/jdbc 双实现共用契约，命名取舍同 {@link OrgRepository}）。
 *
 * @author oatelauser
 */
public interface InstallationRepository {

    /**
     * 按主键查找。
     *
     * @param id 安装 id
     * @return 安装，不存在返回 null
     */
    @Nullable
    Installation findById(String id);

    /**
     * 按唯一键 (registered_client_id, org_id) 查找（V2 唯一约束保证至多一行）。
     *
     * @param registeredClientId oauth2_registered_client.id
     * @param orgId org id
     * @return 安装，不存在返回 null
     */
    @Nullable
    Installation findByClientAndOrg(String registeredClientId, String orgId);

    /**
     * 查某客户端的全部安装行（各状态皆含）——ceiling 候选筛（OrgScopeGate）用单查取代逐 org 查询的口径。
     *
     * @param registeredClientId oauth2_registered_client.id
     * @return 该 client 的安装行列表（无安装为空列表）
     */
    List<Installation> findByClient(String registeredClientId);

    /**
     * 新增安装行（首发起）。同 (client, org) 已有行由服务层按状态机分流到 {@link #update}，唯一键冲突
     * 由 DB 约束兜底。
     *
     * @param installation 完整安装实体
     */
    void save(Installation installation);

    /**
     * 按主键整行更新（状态转移与重发重置：status/ceiling/requested/approved 各列，键列与 created_at 不动）。
     *
     * @param installation 完整安装实体
     */
    void update(Installation installation);
}
