package io.github.oatelauser.jauth.selfservice.web;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.util.Assert;

/**
 * 已授权应用看板的只读查询面（SPEC §5 横切：授权看板 + 一键 Revoke）。
 *
 * <p><b>为何绕开框架接口直查 SQL</b>：{@link OAuth2AuthorizationService} 契约只有
 * findById/findByToken/save/remove，没有按 principal 枚举能力（内存实现也无对外视图）——看板是框架未覆盖的查询面，
 * 故本类持一条只读 SQL 查 oauth2_authorization（列均为框架公开 schema，无写路径）。
 *
 * <p><b>Revoke 的两条腿</b>（授权 remove + consent remove）在 {@link AuthorizedAppsController} 编排：本类保持
 * 只读，不持有可变框架服务。"最近使用"列暂缺：B7 审计上线后由 jauth_audit_event 回填。
 *
 * <p><b>装配门控</b>：只在 {@code jauth-hub.storage=jdbc} 注册（数据只在 DB 有按 principal 的查询面；memory
 * 模式页面渲染不支持提示）。
 *
 * @author oatelauser
 */
public class AuthorizedAppService {

    /** 聚合中间行：一列授权的原始投影，Java 侧按 client 聚合（避免 H2/PG 方言化的聚合 SQL）。 */
    private record AuthorizationRow(String id, String registeredClientId, Set<String> scopes, Instant latestIssuedAt) {}

    private final JdbcOperations jdbcOperations;

    public AuthorizedAppService(JdbcOperations jdbcOperations) {
        this.jdbcOperations = jdbcOperations;
    }

    /**
     * 列出 principal 的授权概览（按 client 聚合，最近授权时间倒序）。
     *
     * @param principalName 主体名（= 登录名，与 oauth2_authorization.principal_name 同键）
     * @return 每个 client 一行
     */
    public List<AuthorizedApp> list(String principalName) {
        Assert.hasText(principalName, "principalName cannot be empty");
        Map<String, Set<String>> scopesByClient = new LinkedHashMap<>();
        Map<String, Instant> latestByClient = new LinkedHashMap<>();
        for (AuthorizationRow row : queryRows(principalName, null)) {
            scopesByClient
                    .computeIfAbsent(row.registeredClientId(), key -> new LinkedHashSet<>())
                    .addAll(row.scopes());
            latestByClient.merge(row.registeredClientId(), row.latestIssuedAt(), AuthorizedAppService::latest);
        }
        List<AuthorizedApp> apps = new ArrayList<>(scopesByClient.size());
        for (Map.Entry<String, Set<String>> entry : scopesByClient.entrySet()) {
            apps.add(new AuthorizedApp(entry.getKey(), entry.getValue(), latestByClient.get(entry.getKey())));
        }
        apps.sort(
                Comparator.comparing(AuthorizedApp::lastAuthorizedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return apps;
    }

    /**
     * 该 (principal, client) 的全部授权行 id——Revoke 编排的第一腿输入（remove 走框架服务，id 列表走本查询）。
     *
     * @param principalName 主体名
     * @param registeredClientId 客户端注册 id（oauth2_registered_client.id）
     * @return 授权行 id 列表（可能为空）
     */
    public List<String> authorizationIds(String principalName, String registeredClientId) {
        Assert.hasText(principalName, "principalName cannot be empty");
        Assert.hasText(registeredClientId, "registeredClientId cannot be empty");
        List<String> ids = new ArrayList<>();
        for (AuthorizationRow row : queryRows(principalName, registeredClientId)) {
            ids.add(row.id());
        }
        return List.copyOf(ids);
    }

    /** 只读投影查询：framework 表无 authorized_at 列（在 attributes 大对象里），取三类令牌签发时间的最新者近似。 */
    private List<AuthorizationRow> queryRows(String principalName, String registeredClientIdFilter) {
        StringBuilder sql =
                new StringBuilder("SELECT id, registered_client_id, authorized_scopes, access_token_issued_at,"
                        + " authorization_code_issued_at, refresh_token_issued_at FROM oauth2_authorization"
                        + " WHERE principal_name = ?");
        List<Object> args = new ArrayList<>(2);
        args.add(principalName);
        if (registeredClientIdFilter != null) {
            sql.append(" AND registered_client_id = ?");
            args.add(registeredClientIdFilter);
        }
        return this.jdbcOperations.query(sql.toString(), this::mapRow, args.toArray());
    }

    private AuthorizationRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AuthorizationRow(
                rs.getString("id"),
                rs.getString("registered_client_id"),
                splitScopes(rs.getString("authorized_scopes")),
                latest(
                        toInstant(rs.getTimestamp("access_token_issued_at")),
                        toInstant(rs.getTimestamp("authorization_code_issued_at")),
                        toInstant(rs.getTimestamp("refresh_token_issued_at"))));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Instant latest(Instant left, Instant right) {
        if (left == null) {
            return right;
        }
        return right == null || left.isAfter(right) ? left : right;
    }

    private static Instant latest(Instant first, Instant second, Instant third) {
        return latest(latest(first, second), third);
    }

    /** 框架 authorized_scopes 列按逗号拼接（JdbcOAuth2AuthorizationService 的 String.join(",")），空格一并容忍。 */
    private static Set<String> splitScopes(String joined) {
        if (joined == null || joined.isBlank()) {
            return Set.of();
        }
        return Set.of(joined.trim().split("[,\\s]+"));
    }
}
