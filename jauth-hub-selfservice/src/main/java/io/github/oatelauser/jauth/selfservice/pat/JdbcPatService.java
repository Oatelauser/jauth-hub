package io.github.oatelauser.jauth.selfservice.pat;

import io.github.oatelauser.jauth.core.response.JauthErrorCode;
import io.github.oatelauser.jauth.core.response.JauthException;
import io.github.oatelauser.jauth.core.token.TokenHash;
import io.github.oatelauser.jauth.core.util.UuidV7;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.util.Assert;

/**
 * PAT 存储 JDBC 实现（表 jauth_pat）。
 *
 * <p>装配说明：本类不读表元数据（构造零 SQL），表由 starter 的 Flyway 迁移在建——context 刷新完毕才对外服务请求，
 * 首个请求到达时表必已在（与 starter 内 FlywayMigrationGuard 的"先迁移后建仓储"同一时序保证，本模块不重复设守卫）。
 *
 * <p>哈希手术：token_sha256 落 {@link TokenHash#sha256Hex}，与 oauth2_authorization 的令牌列同款纪律。
 *
 * @author oatelauser
 */
public class JdbcPatService implements PatService {

    private static final RowMapper<PatRecord> PAT_ROW_MAPPER = new RowMapper<>() {
        @Override
        public PatRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new PatRecord(
                    rs.getString("id"),
                    rs.getString("user_id"),
                    rs.getString("name"),
                    rs.getString("token_prefix"),
                    splitScopes(rs.getString("scopes")),
                    PatStatus.valueOf(rs.getString("status")),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("expires_at").toInstant(),
                    rs.getTimestamp("last_used_at") == null
                            ? null
                            : rs.getTimestamp("last_used_at").toInstant());
        }
    };

    private final JdbcOperations jdbcOperations;

    private final Clock clock;

    public JdbcPatService(JdbcOperations jdbcOperations, Clock clock) {
        this.jdbcOperations = jdbcOperations;
        this.clock = clock;
    }

    @Override
    public PatIssuance create(String userId, String name, Set<String> scopes, Duration validity) {
        Assert.hasText(userId, "userId cannot be empty");
        Assert.hasText(name, "name cannot be empty");
        Assert.notEmpty(scopes, "scopes cannot be empty");
        Assert.notNull(validity, "validity cannot be null");
        Instant now = this.clock.instant();
        String rawToken = PatTokens.generate();
        PatRecord record = new PatRecord(
                UuidV7.generate().toString(),
                userId,
                name.trim(),
                PatTokens.displayPrefix(rawToken),
                scopes,
                PatStatus.ACTIVE,
                now,
                now.plus(validity),
                null);
        this.jdbcOperations.update(
                "INSERT INTO jauth_pat (id, user_id, name, token_sha256, token_prefix, scopes,"
                        + " expires_at, last_used_at, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)",
                record.id(),
                record.userId(),
                record.name(),
                TokenHash.sha256Hex(rawToken),
                record.tokenPrefix(),
                joinScopes(record.scopes()),
                Timestamp.from(record.expiresAt()),
                record.status().name(),
                Timestamp.from(record.createdAt()));
        return new PatIssuance(record, rawToken);
    }

    @Override
    public List<PatRecord> listActive(String userId) {
        Assert.hasText(userId, "userId cannot be empty");
        return this.jdbcOperations.query(
                "SELECT id, user_id, name, token_prefix, scopes, expires_at, last_used_at, status,"
                        + " created_at FROM jauth_pat WHERE user_id = ? AND status = ? ORDER BY created_at DESC, id ASC",
                PAT_ROW_MAPPER,
                userId,
                PatStatus.ACTIVE.name());
    }

    @Override
    public void revoke(String userId, String patId) {
        Assert.hasText(userId, "userId cannot be empty");
        Assert.hasText(patId, "patId cannot be empty");
        int updated = this.jdbcOperations.update(
                "UPDATE jauth_pat SET status = ? WHERE id = ? AND user_id = ? AND status = ?",
                PatStatus.REVOKED.name(),
                patId,
                userId,
                PatStatus.ACTIVE.name());
        if (updated == 0) {
            // 不存在、非本人、已吊销三种情形对调用方同义：记录不可再吊销
            throw new JauthException(JauthErrorCode.B0502);
        }
    }

    private static String joinScopes(Set<String> scopes) {
        return String.join(" ", scopes);
    }

    private static Set<String> splitScopes(String joined) {
        if (joined == null || joined.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(joined.trim().split("\\s+")).collect(Collectors.toUnmodifiableSet());
    }
}
