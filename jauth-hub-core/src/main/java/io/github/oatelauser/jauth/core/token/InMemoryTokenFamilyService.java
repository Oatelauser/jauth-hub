package io.github.oatelauser.jauth.core.token;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.springframework.util.Assert;

/**
 * 刷新令牌族谱存储的内存实现（memory 存储模式配对 {@link JdbcTokenFamilyService} 的 API）。
 *
 * <p>语义与 JDBC 版一致（{@link JdbcTokenFamilyService} 类注释）：族 = (principal_name,
 * registered_client_id)，每轮转记一行哈希、重放探测命中即烧族。区别仅在持久性—— 重启即失，与 memory
 * 模式"重启丢授权状态"的既定语义一致（SPEC §1 存储决议），故无多实例并发轮转场景， {@code
 * DuplicateKeyException} 式兜底不需要。
 *
 * <p>并发模型：方法级 synchronized（p3c 并发章）。内存模式面向单实例 demo 规模，令牌签发 QPS
 * 远低于简单锁的吞吐上限；{@link ConcurrentHashMap} + 分族锁的复杂度不值得（ponytail: 方法锁，
 * 换分族细锁的时机是 memory 模式承载生产流量——那本身就该切 jdbc 模式）。
 *
 * <p>B4 装配说明：本类是 B4 批次向 core 补的唯一一个类（内存族谱），memory 模式的授权服务包装 在 starter
 * 装配层消费本类实现 RTR 熔断探测。
 *
 * @author oatelauser
 */
public class InMemoryTokenFamilyService {

    /** ACTIVE（最新一代，正在使用）。语义与 JDBC 版词表一致。 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** SUPERSEDED（已被新一代顶替，哈希保留供重放检测）。 */
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    /** BURNED（检测到重放，整族烧断——该 user+client 须重新登录授权）。 */
    public static final String STATUS_BURNED = "BURNED";

    /** 哈希 → 行。哈希全局唯一（SHA-256），行内带族键供烧族反向定位。 */
    private final Map<String, FamilyRow> rowsByHash = new ConcurrentHashMap<>();

    /** 族键 → 代号发生器。代号仅作可读性，不参与判定（与 JDBC 版一致）。 */
    private final Map<String, AtomicInteger> generationsByFamily = new ConcurrentHashMap<>();

    /**
     * 授权 id 索引：principal → 该主体全部授权 id（v1.3 D1）。内存模式的授权对象只活在授权服务装饰链
     * 最内层、无法按主体枚举，本索引由 starter 的 FamilyAware 包装在 save/remove 侧维护，供按主体的
     * 全量清剿（InMemoryPrincipalAuthorizationRevoker）枚举。陈旧项无害：清剿走 findById 落空即跳过。
     */
    private final Map<String, Set<String>> authorizationIdsByPrincipal = new ConcurrentHashMap<>();

    /**
     * 记录一次刷新令牌轮转：哈希已存在即跳过（幂等），否则插为新一代 ACTIVE 并把族内其余 ACTIVE 行降
     * SUPERSEDED。
     *
     * @param principalName 主体名（= 登录名）
     * @param registeredClientId 客户端 id
     * @param refreshTokenHash refresh token 的 SHA-256 十六进制
     */
    public synchronized void recordRefreshToken(
            String principalName, String registeredClientId, String refreshTokenHash) {
        Assert.hasText(principalName, "principalName cannot be empty");
        Assert.hasText(registeredClientId, "registeredClientId cannot be empty");
        Assert.hasText(refreshTokenHash, "refreshTokenHash cannot be empty");
        if (rowsByHash.containsKey(refreshTokenHash)) {
            return;
        }
        demoteActiveRows(principalName, registeredClientId);
        rowsByHash.put(
                refreshTokenHash,
                new FamilyRow(
                        principalName,
                        registeredClientId,
                        nextGeneration(principalName, registeredClientId),
                        STATUS_ACTIVE));
    }

    /**
     * 按哈希探测族谱（重放检测入口）。含 BURNED 行：烧断后的继续重放同样命中（重复烧为幂等空操作）。
     *
     * @param refreshTokenHash refresh token 的 SHA-256 十六进制
     * @return 命中的族谱行；从未见过的哈希返回 empty（正常未知令牌，不烧）
     */
    public Optional<FamilyRow> findByRefreshTokenHash(String refreshTokenHash) {
        Assert.hasText(refreshTokenHash, "refreshTokenHash cannot be empty");
        return Optional.ofNullable(rowsByHash.get(refreshTokenHash));
    }

    /**
     * 烧族：该 user+client 的全部族谱行标 BURNED。
     *
     * @param principalName 主体名
     * @param registeredClientId 客户端 id
     * @return 标记行数
     */
    public synchronized int burnFamily(String principalName, String registeredClientId) {
        Assert.hasText(principalName, "principalName cannot be empty");
        Assert.hasText(registeredClientId, "registeredClientId cannot be empty");
        return markBurned(row -> row.principalName().equals(principalName)
                && row.registeredClientId().equals(registeredClientId));
    }

    /**
     * 烧断该主体的全部族谱行并清其授权 id 索引（v1.3 D1，按主体全量清剿；JDBC 版镜像方法无索引——SQL
     * 直删授权行无需枚举）。
     *
     * @param principalName 主体名
     * @return 标记 BURNED 的行数
     */
    public synchronized int burnAllByPrincipal(String principalName) {
        Assert.hasText(principalName, "principalName cannot be empty");
        authorizationIdsByPrincipal.remove(principalName);
        return markBurned(row -> row.principalName().equals(principalName));
    }

    /**
     * 烧断该 client 的全部族谱行（跨主体，v1.3 D2 应用删除级联；JDBC 版镜像方法）。
     *
     * @param registeredClientId 客户端 id
     * @return 标记行数
     */
    public synchronized int burnAllByClient(String registeredClientId) {
        Assert.hasText(registeredClientId, "registeredClientId cannot be empty");
        return markBurned(row -> row.registeredClientId().equals(registeredClientId));
    }

    /**
     * 记一条授权归属（FamilyAware 包装的 save 侧调用，授权 id 索引维护）。幂等。
     *
     * @param principalName 主体名
     * @param authorizationId 授权 id
     */
    public synchronized void recordAuthorizationId(String principalName, String authorizationId) {
        Assert.hasText(principalName, "principalName cannot be empty");
        Assert.hasText(authorizationId, "authorizationId cannot be empty");
        authorizationIdsByPrincipal
                .computeIfAbsent(principalName, key -> ConcurrentHashMap.newKeySet())
                .add(authorizationId);
    }

    /**
     * 撤一条授权归属（FamilyAware 包装的 remove 侧调用）。缺席为无害空操作。
     *
     * @param principalName 主体名
     * @param authorizationId 授权 id
     */
    public synchronized void discardAuthorizationId(String principalName, String authorizationId) {
        Assert.hasText(principalName, "principalName cannot be empty");
        Assert.hasText(authorizationId, "authorizationId cannot be empty");
        Set<String> ids = authorizationIdsByPrincipal.get(principalName);
        if (ids != null) {
            ids.remove(authorizationId);
        }
    }

    /**
     * 该主体的全部已知授权 id（快照副本；陈旧项由消费方 findById 落空跳过）。
     *
     * @param principalName 主体名
     * @return 授权 id 集（无记录为空集）
     */
    public Set<String> authorizationIdsByPrincipal(String principalName) {
        Assert.hasText(principalName, "principalName cannot be empty");
        Set<String> ids = authorizationIdsByPrincipal.get(principalName);
        return ids == null ? Set.of() : Set.copyOf(ids);
    }

    /** 命中即标 BURNED 的共享烧行循环（burnFamily / burnAllByPrincipal 同体）。 */
    private synchronized int markBurned(Predicate<FamilyRow> match) {
        int burned = 0;
        for (Map.Entry<String, FamilyRow> entry : rowsByHash.entrySet()) {
            FamilyRow row = entry.getValue();
            if (match.test(row)) {
                entry.setValue(
                        new FamilyRow(row.principalName(), row.registeredClientId(), row.generation(), STATUS_BURNED));
                burned++;
            }
        }
        return burned;
    }

    private void demoteActiveRows(String principalName, String registeredClientId) {
        rowsByHash.replaceAll((hash, row) -> row.principalName().equals(principalName)
                        && row.registeredClientId().equals(registeredClientId)
                        && STATUS_ACTIVE.equals(row.status())
                ? new FamilyRow(row.principalName(), row.registeredClientId(), row.generation(), STATUS_SUPERSEDED)
                : row);
    }

    private int nextGeneration(String principalName, String registeredClientId) {
        return generationsByFamily
                .computeIfAbsent(familyKey(principalName, registeredClientId), key -> new AtomicInteger())
                .incrementAndGet();
    }

    private static String familyKey(String principalName, String registeredClientId) {
        return principalName + " " + registeredClientId;
    }

    /**
     * 族谱行（与 JDBC 版同形的最小投影）。
     *
     * @param principalName 主体名
     * @param registeredClientId 客户端 id
     * @param generation 代号（1 起）
     * @param status ACTIVE / SUPERSEDED / BURNED
     */
    public record FamilyRow(String principalName, String registeredClientId, int generation, String status) {}
}
