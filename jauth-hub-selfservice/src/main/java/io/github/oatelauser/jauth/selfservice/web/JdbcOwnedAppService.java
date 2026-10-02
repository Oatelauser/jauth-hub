package io.github.oatelauser.jauth.selfservice.web;

import io.github.oatelauser.jauth.core.client.ClientOwner;
import io.github.oatelauser.jauth.core.client.JauthJdbcRegisteredClientRepository;
import io.github.oatelauser.jauth.core.response.JauthErrorCode;
import io.github.oatelauser.jauth.core.response.JauthException;
import io.github.oatelauser.jauth.core.scope.ScopeCatalog;
import io.github.oatelauser.jauth.core.token.JdbcTokenFamilyService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Set;
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

    private final JdbcTokenFamilyService tokenFamilyService;

    public JdbcOwnedAppService(
            JauthJdbcRegisteredClientRepository clientRepository,
            JdbcOperations jdbcOperations,
            @Nullable TransactionOperations transactionOperations,
            JdbcTokenFamilyService tokenFamilyService,
            PasswordEncoder passwordEncoder,
            ScopeCatalog scopeCatalog,
            Clock clock) {
        super(passwordEncoder, scopeCatalog, clock);
        // 委托以函数捕获（JdbcClientOwnerResolver 同款取舍）：仓储类带 save 可变方法，存成员即 SpotBugs
        // EI_EXPOSE_REP2 暴露内部表示
        this.clientSaveWithOwner = clientRepository::save;
        this.jdbcOperations = jdbcOperations;
        this.transactionOperations = transactionOperations;
        this.tokenFamilyService = tokenFamilyService;
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

    @Override
    protected OwnedApp persistSecretRotation(ClientOwner owner, String appId, String encodedSecret) {
        // 机密性并入 WHERE：client_authentication_methods 含 client_secret_basic 才有 secret 可轮，
        // 公开应用/不存在/非本人三种 miss 同译 B0502（单语句，免先查后改的竞态窗口）
        int updated = this.jdbcOperations.update(
                "UPDATE oauth2_registered_client SET client_secret = ? WHERE id = ? AND " + ownerColumn(owner)
                        + " = ? AND client_authentication_methods LIKE '%client_secret_basic%'",
                encodedSecret,
                appId,
                ownerValue(owner));
        if (updated == 0) {
            throw new JauthException(JauthErrorCode.B0502);
        }
        return findOwnedApp(owner, appId);
    }

    @Override
    protected OwnedApp persistUpdate(ClientOwner owner, String appId, String name, Set<String> redirectUris) {
        // redirect_uris 框架口径 = 逗号拼接（splitRedirectUris 对偶）
        int updated = this.jdbcOperations.update(
                "UPDATE oauth2_registered_client SET client_name = ?, redirect_uris = ? WHERE id = ? AND "
                        + ownerColumn(owner) + " = ?",
                name,
                String.join(",", redirectUris),
                appId,
                ownerValue(owner));
        if (updated == 0) {
            throw new JauthException(JauthErrorCode.B0502);
        }
        return findOwnedApp(owner, appId);
    }

    @Override
    protected void persistDelete(ClientOwner owner, String appId) {
        Runnable cascade = () -> {
            Integer owned = this.jdbcOperations.queryForObject(
                    "SELECT COUNT(*) FROM oauth2_registered_client WHERE id = ? AND " + ownerColumn(owner) + " = ?",
                    Integer.class,
                    appId,
                    ownerValue(owner));
            if (owned == null || owned == 0) {
                throw new JauthException(JauthErrorCode.B0502);
            }
            // 先删授权后烧族（级联顺序约束，中断停安全态）；consent/安装随行删除
            this.jdbcOperations.update("DELETE FROM oauth2_authorization WHERE registered_client_id = ?", appId);
            this.jdbcOperations.update(
                    "DELETE FROM oauth2_authorization_consent WHERE registered_client_id = ?", appId);
            this.jdbcOperations.update("DELETE FROM jauth_installation WHERE registered_client_id = ?", appId);
            this.tokenFamilyService.burnAllByClient(appId);
            this.jdbcOperations.update("DELETE FROM oauth2_registered_client WHERE id = ?", appId);
        };
        if (this.transactionOperations != null) {
            this.transactionOperations.executeWithoutResult(status -> cascade.run());
        } else {
            cascade.run();
        }
    }

    /** 单行回读（UPDATE 后取最新视图；miss 一律 B0502）。 */
    private OwnedApp findOwnedApp(ClientOwner owner, String appId) {
        List<OwnedApp> apps = this.jdbcOperations.query(
                "SELECT id, client_id, client_name, client_authentication_methods, redirect_uris,"
                        + " client_id_issued_at FROM oauth2_registered_client WHERE id = ? AND "
                        + ownerColumn(owner) + " = ?",
                OWNED_APP_ROW_MAPPER,
                appId,
                ownerValue(owner));
        return apps.stream().findFirst().orElseThrow(() -> new JauthException(JauthErrorCode.B0502));
    }

    /** owner 维度的列名（个人 owner_user_id / 组织 owner_org_id；platform 归属不进本面，Assert 挡）。 */
    private static String ownerColumn(ClientOwner owner) {
        return owner.userId() != null ? "owner_user_id" : "owner_org_id";
    }

    private static String ownerValue(ClientOwner owner) {
        String value = owner.userId() != null ? owner.userId() : owner.orgId();
        Assert.hasText(value, "personal/org owner required: platform-owned clients are not manageable here");
        return value;
    }

    /** 框架 redirect_uris 列按逗号拼接（JdbcRegisteredClientRepository 的 String.join(",")）。 */
    private static List<String> splitRedirectUris(String joined) {
        return joined == null || joined.isBlank()
                ? List.of()
                : List.of(joined.trim().split(","));
    }
}
