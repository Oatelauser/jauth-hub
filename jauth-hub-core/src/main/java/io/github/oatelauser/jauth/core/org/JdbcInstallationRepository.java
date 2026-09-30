package io.github.oatelauser.jauth.core.org;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.util.Assert;

/**
 * {@link InstallationRepository} 的 JDBC 实现（表 jauth_installation，自持 SQL，不引 ORM）。
 *
 * <p>scopes 两列（ceiling_scopes/requested_scopes）为 TEXT 空格分隔序列化，惯例同 client/PAT 的 scopes 列。
 *
 * @author oatelauser
 */
public class JdbcInstallationRepository implements InstallationRepository {

    private static final String COLUMN_NAMES = "id, registered_client_id, org_id, status, ceiling_scopes, requested_by,"
            + " requested_scopes, approved_by, approved_at, created_at";

    private static final String FIND_BY_ID_SQL = "SELECT " + COLUMN_NAMES + " FROM jauth_installation WHERE id = ?";

    private static final String FIND_BY_CLIENT_AND_ORG_SQL =
            "SELECT " + COLUMN_NAMES + " FROM jauth_installation WHERE registered_client_id = ? AND org_id = ?";

    private static final String FIND_BY_CLIENT_SQL =
            "SELECT " + COLUMN_NAMES + " FROM jauth_installation WHERE registered_client_id = ?";

    private static final String INSERT_SQL =
            "INSERT INTO jauth_installation (" + COLUMN_NAMES + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /** 键列（id/两外键）与 created_at 不可变，update 只重写状态机相关六列。 */
    private static final String UPDATE_SQL =
            "UPDATE jauth_installation SET status = ?, ceiling_scopes = ?, requested_by = ?,"
                    + " requested_scopes = ?, approved_by = ?, approved_at = ? WHERE id = ?";

    private static final RowMapper<Installation> INSTALLATION_ROW_MAPPER = (rs, rowNum) -> new Installation(
            rs.getString("id"),
            rs.getString("registered_client_id"),
            rs.getString("org_id"),
            InstallationStatus.valueOf(rs.getString("status")),
            splitScopes(rs.getString("ceiling_scopes")),
            rs.getString("requested_by"),
            splitScopes(rs.getString("requested_scopes")),
            rs.getString("approved_by"),
            readInstant(rs, "approved_at"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcOperations jdbcOperations;

    public JdbcInstallationRepository(JdbcOperations jdbcOperations) {
        Assert.notNull(jdbcOperations, "jdbcOperations cannot be null");
        this.jdbcOperations = jdbcOperations;
    }

    @Override
    public @Nullable Installation findById(String id) {
        Assert.hasText(id, "id cannot be empty");
        return queryOne(FIND_BY_ID_SQL, id);
    }

    @Override
    public @Nullable Installation findByClientAndOrg(String registeredClientId, String orgId) {
        Assert.hasText(registeredClientId, "registeredClientId cannot be empty");
        Assert.hasText(orgId, "orgId cannot be empty");
        List<Installation> result = this.jdbcOperations.query(
                FIND_BY_CLIENT_AND_ORG_SQL, INSTALLATION_ROW_MAPPER, registeredClientId, orgId);
        return result.isEmpty() ? null : result.get(0);
    }

    @Override
    public List<Installation> findByClient(String registeredClientId) {
        Assert.hasText(registeredClientId, "registeredClientId cannot be empty");
        return this.jdbcOperations.query(FIND_BY_CLIENT_SQL, INSTALLATION_ROW_MAPPER, registeredClientId);
    }

    @Override
    public void save(Installation installation) {
        Assert.notNull(installation, "installation cannot be null");
        this.jdbcOperations.update(INSERT_SQL, ps -> {
            ps.setString(1, installation.id());
            ps.setString(2, installation.registeredClientId());
            ps.setString(3, installation.orgId());
            ps.setString(4, installation.status().name());
            ps.setString(5, joinScopes(installation.ceilingScopes()));
            ps.setString(6, installation.requestedBy());
            ps.setString(7, joinScopes(installation.requestedScopes()));
            ps.setString(8, installation.approvedBy());
            setNullableTimestamp(ps, 9, installation.approvedAt());
            ps.setTimestamp(10, Timestamp.from(installation.createdAt()));
        });
    }

    @Override
    public void update(Installation installation) {
        Assert.notNull(installation, "installation cannot be null");
        this.jdbcOperations.update(UPDATE_SQL, ps -> {
            ps.setString(1, installation.status().name());
            ps.setString(2, joinScopes(installation.ceilingScopes()));
            ps.setString(3, installation.requestedBy());
            ps.setString(4, joinScopes(installation.requestedScopes()));
            ps.setString(5, installation.approvedBy());
            setNullableTimestamp(ps, 6, installation.approvedAt());
            ps.setString(7, installation.id());
        });
    }

    private @Nullable Installation queryOne(String sql, String id) {
        List<Installation> result = this.jdbcOperations.query(sql, INSTALLATION_ROW_MAPPER, id);
        return result.isEmpty() ? null : result.get(0);
    }

    private static String joinScopes(Set<String> scopes) {
        return String.join(" ", scopes);
    }

    private static Set<String> splitScopes(@Nullable String joined) {
        if (joined == null || joined.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(joined.trim().split("\\s+")).collect(Collectors.toUnmodifiableSet());
    }

    private static @Nullable Instant readInstant(ResultSet rs, String columnName) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(columnName);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static void setNullableTimestamp(PreparedStatement ps, int index, @Nullable Instant instant)
            throws SQLException {
        ps.setTimestamp(index, instant == null ? null : Timestamp.from(instant));
    }
}
