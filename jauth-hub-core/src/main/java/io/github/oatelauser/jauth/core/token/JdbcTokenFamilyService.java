package io.github.oatelauser.jauth.core.token;

import io.github.oatelauser.jauth.core.util.UuidV7;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.util.Assert;

/**
 * 刷新令牌族谱存储（表 jauth_token_family，V5 行模型：一轮转一行）。
 *
 * <p>族 = (principal_name, registered_client_id)。每轮转插一行（generation 递增，最新行 ACTIVE）， 旧行转 SUPERSEDED
 * 但哈希保留——任何一代旧 refresh token 的哈希都必须查得到，重放才可检测（RTR 熔断前提）。
 *
 * <p>身份键用 principal_name 而非 user id：框架授权表（oauth2_authorization）只有 principal_name， 烧族时才能一条 DELETE
 * 清掉该 user+client 的全部授权（V5 迁移注释有完整理由）。
 *
 * <p><b>并发兜底</b>：recordRefreshToken 幂等——同哈希已存在即跳过；两实例同时记录同一次轮转会撞 refresh_token_hash 唯一约束，{@link
 * DuplicateKeyException} 视为对端已记录，静默放弃。 同族并发轮转产生两行 ACTIVE 时，下一次 recordRefreshToken 的"除本行外全部
 * SUPERSEDED"自愈收敛。
 *
 * @author oatelauser
 */
public class JdbcTokenFamilyService {

    /** ACTIVE（最新一代，正在使用）。 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** SUPERSEDED（已被新一代顶替，哈希保留供重放检测）。 */
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    /** BURNED（检测到重放，整族烧断——该 user+client 须重新登录授权）。 */
    public static final String STATUS_BURNED = "BURNED";

    private static final RowMapper<FamilyRow> FAMILY_ROW_MAPPER = new RowMapper<>() {
        @Override
        public FamilyRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new FamilyRow(
                    rs.getString("principal_name"),
                    rs.getString("registered_client_id"),
                    rs.getInt("generation"),
                    rs.getString("status"));
        }
    };

    private final JdbcOperations jdbcOperations;

    public JdbcTokenFamilyService(JdbcOperations jdbcOperations) {
        this.jdbcOperations = jdbcOperations;
    }

    /**
     * 记录一次刷新令牌轮转（save 路径调用）：哈希已存在则跳过（幂等，覆盖吊销等再 save 场景）， 否则插为新一代 ACTIVE 并把族内其余 ACTIVE 行降
     * SUPERSEDED。
     *
     * @param principalName 主体名（= 登录名）
     * @param registeredClientId 客户端 id
     * @param refreshTokenHash refresh token 的 SHA-256 十六进制
     */
    public void recordRefreshToken(String principalName, String registeredClientId, String refreshTokenHash) {
        if (findByRefreshTokenHash(refreshTokenHash).isPresent()) {
            return;
        }
        try {
            jdbcOperations.update(
                    "INSERT INTO jauth_token_family (id, principal_name, registered_client_id,"
                            + " generation, status, refresh_token_hash, created_at) VALUES (?, ?, ?, ?,"
                            + " ?, ?, ?)",
                    UuidV7.generate().toString(),
                    principalName,
                    registeredClientId,
                    nextGeneration(principalName, registeredClientId),
                    STATUS_ACTIVE,
                    refreshTokenHash,
                    Timestamp.from(Instant.now()));
        } catch (DuplicateKeyException ex) {
            // 对端实例已记录同一次轮转（refresh_token_hash 唯一冲突），放弃即幂等
        }
        jdbcOperations.update(
                "UPDATE jauth_token_family SET status = ?, updated_at = ? WHERE principal_name = ?"
                        + " AND registered_client_id = ? AND status = ? AND refresh_token_hash <> ?",
                STATUS_SUPERSEDED,
                Timestamp.from(Instant.now()),
                principalName,
                registeredClientId,
                STATUS_ACTIVE,
                refreshTokenHash);
    }

    /**
     * 按哈希探测族谱（重放检测入口）。含 BURNED 行：烧断后的继续重放同样命中（重复烧为幂等空操作）。
     *
     * @param refreshTokenHash refresh token 的 SHA-256 十六进制
     * @return 命中的族谱行；从未见过的哈希返回 empty（正常未知令牌，不烧）
     */
    public Optional<FamilyRow> findByRefreshTokenHash(String refreshTokenHash) {
        List<FamilyRow> rows = jdbcOperations.query(
                "SELECT principal_name, registered_client_id, generation, status FROM"
                        + " jauth_token_family WHERE refresh_token_hash = ?",
                FAMILY_ROW_MAPPER,
                refreshTokenHash);
        return rows.stream().findFirst();
    }

    /**
     * 烧族：该 user+client 的全部族谱行标 BURNED。烧断后用户须重新登录授权（语义见授权服务 javadoc）。
     *
     * @param principalName 主体名
     * @param registeredClientId 客户端 id
     * @return 标记行数
     */
    public int burnFamily(String principalName, String registeredClientId) {
        return jdbcOperations.update(
                "UPDATE jauth_token_family SET status = ?, updated_at = ? WHERE principal_name = ?"
                        + " AND registered_client_id = ?",
                STATUS_BURNED,
                Timestamp.from(Instant.now()),
                principalName,
                registeredClientId);
    }

    /**
     * 烧断该主体的全部族谱行（v1.3 D1，按主体全量清剿；内存版镜像方法额外清授权 id 索引——JDBC 授权行
     * 由清剿方 SQL 直删，无需枚举）。已 BURNED 行无条件重标（幂等，与 {@link #burnFamily} 同语义）。
     *
     * @param principalName 主体名
     * @return 标记行数
     */
    public int burnAllByPrincipal(String principalName) {
        Assert.hasText(principalName, "principalName cannot be empty");
        return jdbcOperations.update(
                "UPDATE jauth_token_family SET status = ?, updated_at = ? WHERE principal_name = ?",
                STATUS_BURNED,
                Timestamp.from(Instant.now()),
                principalName);
    }

    /**
     * 烧断该 client 的全部族谱行（跨主体，v1.3 D2 应用删除级联；内存版镜像方法）。先于本调用删除该
     * client 的授权行（级联顺序约束同按主体清剿：中断停在安全态）。
     *
     * @param registeredClientId 客户端 id
     * @return 标记行数
     */
    public int burnAllByClient(String registeredClientId) {
        Assert.hasText(registeredClientId, "registeredClientId cannot be empty");
        return jdbcOperations.update(
                "UPDATE jauth_token_family SET status = ?, updated_at = ? WHERE registered_client_id = ?",
                STATUS_BURNED,
                Timestamp.from(Instant.now()),
                registeredClientId);
    }

    /** 下一代号：族内（含 BURNED 历史）最大 generation + 1，从 1 起。 并发双实例可能同代号——探测/烧族只依赖哈希与族键，代号仅作可读性排序，不参与判定。 */
    private int nextGeneration(String principalName, String registeredClientId) {
        Integer max = jdbcOperations.queryForObject(
                "SELECT MAX(generation) FROM jauth_token_family WHERE principal_name = ?"
                        + " AND registered_client_id = ?",
                Integer.class,
                principalName,
                registeredClientId);
        return max == null ? 1 : max + 1;
    }

    /**
     * 族谱行（探测命中的最小投影：烧族只需族键；generation/status 供诊断与测试）。
     *
     * @param principalName 主体名
     * @param registeredClientId 客户端 id
     * @param generation 代号（1 起）
     * @param status ACTIVE / SUPERSEDED / BURNED
     */
    public record FamilyRow(String principalName, String registeredClientId, int generation, String status) {}
}
