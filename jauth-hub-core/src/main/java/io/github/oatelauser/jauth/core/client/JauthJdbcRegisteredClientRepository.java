package io.github.oatelauser.jauth.core.client;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.util.Assert;

/**
 * 框架 {@link JdbcRegisteredClientRepository} 的 owner 两列扩展（表 oauth2_registered_client）。
 *
 * <p>框架 13 列的 SELECT/INSERT/UPDATE 均为 private static final 常量、save 路径私有，无法注入额外列。 若整套 SQL
 * 复制自持，框架升级时的列漂移要靠人工同步。故取更小手术面： 基础 13 列读写完全复用框架实现，owner 两列由本类在 super.save 之后追加一条按主键的 UPDATE 同步—— 自持
 * SQL 仅此两条。代价是两条语句非原子（无事务时崩溃会留下 owner 为 NULL 的行， 等价"平台内置"语义且可重放修复），调用方（seeder/管理服务）自行决定是否包事务。
 *
 * <p>必须在 Flyway 迁移完成后构造：框架构造函数会读取表列元数据决定 LOB 读写策略。
 *
 * @author oatelauser
 */
public class JauthJdbcRegisteredClientRepository extends JdbcRegisteredClientRepository {

    private static final String UPDATE_OWNER_SQL =
            "UPDATE oauth2_registered_client " + "SET owner_user_id = ?, owner_org_id = ? WHERE id = ?";

    private static final String FIND_OWNER_SQL =
            "SELECT owner_user_id, owner_org_id FROM oauth2_registered_client " + "WHERE id = ?";

    private final JdbcOperations jdbcOperations;

    public JauthJdbcRegisteredClientRepository(JdbcOperations jdbcOperations) {
        super(jdbcOperations);
        Assert.notNull(jdbcOperations, "jdbcOperations cannot be null");
        this.jdbcOperations = jdbcOperations;
    }

    /**
     * 保存客户端并同步归属两列。
     *
     * @param registeredClient 客户端
     * @param owner 归属（平台内置用 {@link ClientOwner#platform()}）
     */
    public void save(RegisteredClient registeredClient, ClientOwner owner) {
        Assert.notNull(registeredClient, "registeredClient cannot be null");
        Assert.notNull(owner, "owner cannot be null");
        super.save(registeredClient);
        this.jdbcOperations.update(UPDATE_OWNER_SQL, owner.userId(), owner.orgId(), registeredClient.getId());
    }

    /**
     * 查客户端归属。
     *
     * @param id 客户端主键 id（非 client_id）
     * @return 归属，客户端不存在返回 null
     */
    public @Nullable ClientOwner findOwnerById(String id) {
        Assert.hasText(id, "id cannot be empty");
        List<ClientOwner> result = this.jdbcOperations.query(
                FIND_OWNER_SQL,
                (rs, rowNum) -> new ClientOwner(rs.getString("owner_user_id"), rs.getString("owner_org_id")),
                id);
        return result.isEmpty() ? null : result.get(0);
    }
}
