package io.github.oatelauser.jauth.core.user;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.util.Assert;

/**
 * {@link UserRepository} 的 JDBC 实现（表 jauth_user，自持 SQL，不引 ORM）。
 *
 * @author oatelauser
 */
public class JdbcUserRepository implements UserRepository {

    private static final String COLUMN_NAMES =
            "id, username, password_hash, display_name, email, role, status, " + "strong_auth_at, created_at";

    private static final String FIND_BY_ID_SQL = "SELECT " + COLUMN_NAMES + " FROM jauth_user WHERE id = ?";

    private static final String FIND_BY_USERNAME_SQL = "SELECT " + COLUMN_NAMES + " FROM jauth_user WHERE username = ?";

    private static final String INSERT_SQL =
            "INSERT INTO jauth_user (" + COLUMN_NAMES + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_PASSWORD_HASH_SQL = "UPDATE jauth_user SET password_hash = ? WHERE id = ?";

    private static final String UPDATE_STATUS_SQL = "UPDATE jauth_user SET status = ? WHERE id = ?";

    private static final String UPDATE_ROLE_SQL = "UPDATE jauth_user SET role = ? WHERE id = ?";

    private static final String UPDATE_DISPLAY_NAME_SQL = "UPDATE jauth_user SET display_name = ? WHERE id = ?";

    private static final String FIND_ALL_SQL = "SELECT " + COLUMN_NAMES + " FROM jauth_user ORDER BY username ASC";

    private static final String UPDATE_STRONG_AUTH_AT_SQL = "UPDATE jauth_user SET strong_auth_at = ? WHERE id = ?";

    private static final RowMapper<JauthUser> USER_ROW_MAPPER = (rs, rowNum) -> new JauthUser(
            rs.getString("id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getString("display_name"),
            rs.getString("email"),
            rs.getString("role"),
            rs.getString("status"),
            readInstant(rs, "strong_auth_at"),
            readInstant(rs, "created_at"));

    private final JdbcOperations jdbcOperations;

    public JdbcUserRepository(JdbcOperations jdbcOperations) {
        Assert.notNull(jdbcOperations, "jdbcOperations cannot be null");
        this.jdbcOperations = jdbcOperations;
    }

    @Override
    public @Nullable JauthUser findById(String id) {
        Assert.hasText(id, "id cannot be empty");
        return queryOne(FIND_BY_ID_SQL, id);
    }

    @Override
    public @Nullable JauthUser findByUsername(String username) {
        Assert.hasText(username, "username cannot be empty");
        return queryOne(FIND_BY_USERNAME_SQL, username);
    }

    @Override
    public void save(JauthUser user) {
        Assert.notNull(user, "user cannot be null");
        this.jdbcOperations.update(INSERT_SQL, ps -> {
            ps.setString(1, user.id());
            ps.setString(2, user.username());
            ps.setString(3, user.passwordHash());
            ps.setString(4, user.displayName());
            ps.setString(5, user.email());
            ps.setString(6, user.role());
            ps.setString(7, user.status());
            setNullableTimestamp(ps, 8, user.strongAuthAt());
            ps.setTimestamp(9, Timestamp.from(user.createdAt()));
        });
    }

    @Override
    public void updatePasswordHash(String id, String passwordHash) {
        Assert.hasText(id, "id cannot be empty");
        Assert.hasText(passwordHash, "passwordHash cannot be empty");
        this.jdbcOperations.update(UPDATE_PASSWORD_HASH_SQL, passwordHash, id);
    }

    @Override
    public void updateStatus(String id, String status) {
        Assert.hasText(id, "id cannot be empty");
        Assert.hasText(status, "status cannot be empty");
        this.jdbcOperations.update(UPDATE_STATUS_SQL, status, id);
    }

    @Override
    public void updateRole(String id, String role) {
        Assert.hasText(id, "id cannot be empty");
        Assert.hasText(role, "role cannot be empty");
        this.jdbcOperations.update(UPDATE_ROLE_SQL, role, id);
    }

    @Override
    public void updateDisplayName(String id, @Nullable String displayName) {
        Assert.hasText(id, "id cannot be empty");
        this.jdbcOperations.update(UPDATE_DISPLAY_NAME_SQL, displayName, id);
    }

    @Override
    public List<JauthUser> findAll() {
        return this.jdbcOperations.query(FIND_ALL_SQL, USER_ROW_MAPPER);
    }

    @Override
    public long countAll() {
        // 同表单列聚合,双库兼容(不做 COUNT(*) OVER 窗口技巧)
        Long count = this.jdbcOperations.queryForObject("SELECT COUNT(*) FROM jauth_user", Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public List<JauthUser> findPage(int offset, int limit) {
        Assert.isTrue(offset >= 0, "offset must be >= 0");
        Assert.isTrue(limit > 0, "limit must be > 0");
        // username ASC 定序与 findAll 同源(FIND_ALL_SQL 即 ORDER BY username)
        return this.jdbcOperations.query(FIND_ALL_SQL + " LIMIT ? OFFSET ?", USER_ROW_MAPPER, limit, offset);
    }

    @Override
    public void updateStrongAuthAt(String id, @Nullable Instant strongAuthAt) {
        Assert.hasText(id, "id cannot be empty");
        this.jdbcOperations.update(
                UPDATE_STRONG_AUTH_AT_SQL, strongAuthAt == null ? null : Timestamp.from(strongAuthAt), id);
    }

    private @Nullable JauthUser queryOne(String sql, String argument) {
        List<JauthUser> result = this.jdbcOperations.query(sql, USER_ROW_MAPPER, argument);
        return result.isEmpty() ? null : result.get(0);
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
