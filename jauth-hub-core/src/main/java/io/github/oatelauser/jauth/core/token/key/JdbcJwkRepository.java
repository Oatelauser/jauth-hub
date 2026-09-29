package io.github.oatelauser.jauth.core.token.key;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.util.Assert;

/**
 * 签名密钥仓储（JDBC 实现，表 jauth_jwk）。
 *
 * <p>memory 模式不建内存实现：演示部署密钥本就短命（重启即换，SPEC §3 memory 语义）， 由 B4 装配时直接生成临时密钥，不经本表。
 *
 * <p>{@link #save} 不吞唯一约束冲突：{@link DuplicateKeyException} 上抛给调用方 （{@link JwkRotationService}
 * 以"冲突即放弃"实现多实例并发轮转的兜底）。
 *
 * @author oatelauser
 */
public class JdbcJwkRepository {

    private static final RowMapper<JwkRecord> JWK_ROW_MAPPER = new RowMapper<>() {
        @Override
        public JwkRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp retireAfter = rs.getTimestamp("retire_after");
            return new JwkRecord(
                    rs.getString("id"),
                    rs.getString("kid"),
                    rs.getString("algorithm"),
                    rs.getInt("key_size"),
                    rs.getString("public_key"),
                    rs.getString("private_key"),
                    rs.getString("status"),
                    rs.getTimestamp("created_at").toInstant(),
                    retireAfter == null ? null : retireAfter.toInstant());
        }
    };

    private final JdbcOperations jdbcOperations;

    public JdbcJwkRepository(JdbcOperations jdbcOperations) {
        this.jdbcOperations = jdbcOperations;
    }

    /**
     * 插入新钥（不更新既有行——状态变迁只经 {@link #updateStatus} 走条件 UPDATE，避免读改写竞态）。
     *
     * @param record 完整实体
     * @throws DuplicateKeyException kid 已存在（并发双实例同代轮转的兜底信号）
     */
    public void save(JwkRecord record) {
        jdbcOperations.update(
                "INSERT INTO jauth_jwk (id, kid, algorithm, key_size, public_key, private_key,"
                        + " status, created_at, retire_after) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                record.id(),
                record.kid(),
                record.algorithm(),
                record.keySize(),
                record.publicKey(),
                record.privateKey(),
                record.status(),
                Timestamp.from(record.createdAt()),
                record.retireAfter() == null ? null : Timestamp.from(record.retireAfter()));
    }

    /**
     * 按状态查存活钥，新→旧排序（首位即该状态最新一把）。
     *
     * @param statuses 状态词表（非空）
     * @return 命中行，按 created_at 降序
     */
    public List<JwkRecord> findByStatusIn(String... statuses) {
        Assert.notEmpty(statuses, "statuses cannot be empty");
        String placeholders = String.join(",", Collections.nCopies(statuses.length, "?"));
        return jdbcOperations.query(
                "SELECT id, kid, algorithm, key_size, public_key, private_key, status, created_at,"
                        + " retire_after FROM jauth_jwk WHERE status IN ("
                        + placeholders
                        + ") ORDER BY created_at DESC",
                JWK_ROW_MAPPER,
                (Object[]) statuses);
    }

    /**
     * 单键状态变迁（含可选的 retire_after 赋值）。
     *
     * @param id 主键
     * @param status 目标状态
     * @param retireAfter 新的出网截止时间（RETIRING 必传，其余置 NULL）
     * @return 影响行数（0 = 行已被并发实例变迁过）
     */
    public int updateStatus(String id, String status, @Nullable Instant retireAfter) {
        return jdbcOperations.update(
                "UPDATE jauth_jwk SET status = ?, retire_after = ? WHERE id = ?",
                status,
                retireAfter == null ? null : Timestamp.from(retireAfter),
                id);
    }

    /**
     * 过期 RETIRING 批量转 RETIRED（单条条件 UPDATE，扫描任务每轮执行）。
     *
     * @param now 当前时间
     * @return 本轮转正的行数
     */
    public int retireExpiredRetiring(Instant now) {
        return jdbcOperations.update(
                "UPDATE jauth_jwk SET status = ?, retire_after = NULL" + " WHERE status = ? AND retire_after <= ?",
                JwkRecord.STATUS_RETIRED,
                JwkRecord.STATUS_RETIRING,
                Timestamp.from(now));
    }
}
