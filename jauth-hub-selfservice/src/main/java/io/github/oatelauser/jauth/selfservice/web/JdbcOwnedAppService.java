package io.github.oatelauser.jauth.selfservice.web;

import io.github.oatelauser.jauth.core.client.ClientOwner;
import io.github.oatelauser.jauth.core.client.JauthJdbcRegisteredClientRepository;
import io.github.oatelauser.jauth.core.scope.ScopeCatalog;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.util.Assert;

/**
 * {@link OwnedAppService} 的 JDBC 实现：client 本体走框架 13 列 INSERT、owner 两列走追加 UPDATE（
 * {@link JauthJdbcRegisteredClientRepository#save}），两条语句<b>包进可选事务</b>（B10 滑账①收口：无事务时崩溃
 * 会留 owner 为 NULL 的行，等价"平台内置"语义且难排查；容器有事务管理器即同成同败）。
 *
 * <p>列表是框架未覆盖的查询面（RegisteredClientRepository 契约无按 owner 枚举），照 AuthorizedAppService 先例
 * 持一条只读 SQL 直查 oauth2_registered_client（列均为公开 schema）。owner_user_id 为 CHAR(36) 而 userId 恒为
 * 36 字符 UUID 串，等值比较无 H2 短值补空白问题。
 *
 * @author oatelauser
 */
public class JdbcOwnedAppService extends OwnedAppService {

    private static final String LIST_BY_OWNER_SQL =
            "SELECT id, client_id, client_name, client_authentication_methods, redirect_uris,"
                    + " client_id_issued_at FROM oauth2_registered_client WHERE owner_user_id = ?"
                    + " ORDER BY client_id_issued_at DESC, id ASC";

    /** org 应用列表（B11）：owner 维度换列，其余口径（排序/行映射）与个人列表同款。 */
    private static final String LIST_BY_ORG_SQL =
            "SELECT id, client_id, client_name, client_authentication_methods, redirect_uris,"
                    + " client_id_issued_at FROM oauth2_registered_client WHERE owner_org_id = ?"
                    + " ORDER BY client_id_issued_at DESC, id ASC";

    private static final RowMapper<OwnedApp> OWNED_APP_ROW_MAPPER = new RowMapper<>() {
        @Override
        public OwnedApp mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new OwnedApp(
                    rs.getString("id"),
                    rs.getString("client_id"),
                    rs.getString("client_name"),
                    rs.getString("client_authentication_methods").contains("client_secret_basic"),
                    splitRedirectUris(rs.getString("redirect_uris")),
                    rs.getTimestamp("client_id_issued_at").toInstant());
        }
    };

    private final BiConsumer<RegisteredClient, ClientOwner> clientSaveWithOwner;

    private final JdbcOperations jdbcOperations;

    private final @Nullable TransactionOperations transactionOperations;

    public JdbcOwnedAppService(
            JauthJdbcRegisteredClientRepository clientRepository,
            JdbcOperations jdbcOperations,
            @Nullable TransactionOperations transactionOperations,
            PasswordEncoder passwordEncoder,
            ScopeCatalog scopeCatalog,
            Clock clock) {
        super(passwordEncoder, scopeCatalog, clock);
        // 委托以函数捕获（JdbcClientOwnerResolver 同款取舍）：仓储类带 save 可变方法，存成员即 SpotBugs
        // EI_EXPOSE_REP2 暴露内部表示
        this.clientSaveWithOwner = clientRepository::save;
        this.jdbcOperations = jdbcOperations;
        this.transactionOperations = transactionOperations;
    }

    /** client+owner 两写包可选事务：模板缺席（容器无事务管理器）退化为两条语句直跑，语义同前（见类注释）。 */
    @Override
    protected void persist(RegisteredClient client, ClientOwner owner) {
        if (this.transactionOperations != null) {
            this.transactionOperations.executeWithoutResult(status -> this.clientSaveWithOwner.accept(client, owner));
        } else {
            this.clientSaveWithOwner.accept(client, owner);
        }
    }

    @Override
    public List<OwnedApp> list(String userId) {
        Assert.hasText(userId, "userId cannot be empty");
        return this.jdbcOperations.query(LIST_BY_OWNER_SQL, OWNED_APP_ROW_MAPPER, userId);
    }

    @Override
    public List<OwnedApp> listOrg(String orgId) {
        Assert.hasText(orgId, "orgId cannot be empty");
        return this.jdbcOperations.query(LIST_BY_ORG_SQL, OWNED_APP_ROW_MAPPER, orgId);
    }

    /** 框架 redirect_uris 列按逗号拼接（JdbcRegisteredClientRepository 的 String.join(",")）。 */
    private static List<String> splitRedirectUris(String joined) {
        return joined == null || joined.isBlank()
                ? List.of()
                : List.of(joined.trim().split(","));
    }
}
