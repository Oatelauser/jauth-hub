package io.github.oatelauser.jauth.app.user;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.util.Assert;

/**
 * 按主体的会话全量失效（v1.3 D1）：直删 spring_session（含 spring_session_attributes 子表行，不赌 FK
 * 级联约定）。
 *
 * <p><b>为什么是 SQL 而非 FindByIndexNameSessionRepository</b>：实测 Boot 4.1 的 spring-session-jdbc
 * 默认注册的会话仓库已不实现索引接口（FindByIndexNameSessionRepository 无 bean，
 * {@code spring.session.jdbc.repository-type=indexed} 亦无法拉回）——框架默认与 vendored DDL（V3，带
 * principal_name 索引列）已漂移。principal_name 列是 jauth 自家的 Flyway 承诺，直删最稳、双库兼容；
 * 升级路径：框架恢复索引仓库后可改走仓库接口。
 *
 * <p>keepSessionId 用于自助改密保留当前会话——改密者刚以旧口令+sudo/passkey 自证，当前会话新鲜可信
 * （GitHub「登出其他会话」同款）。管理员重置与停用不保留任何目标会话。本类属 app 壳层（jdbc 模式
 * Flyway 恒建表）；嵌入宿主自管会话不经此类，授权清剿亦不依赖本类，令牌面仍 fail-secure。
 *
 * @author oatelauser
 */
public class UserSessionInvalidator {

    private static final String DELETE_ATTRIBUTES = "DELETE FROM spring_session_attributes WHERE"
            + " session_primary_id IN (SELECT primary_id FROM spring_session WHERE principal_name = ?"
            + " AND primary_id <> COALESCE(?, ''))";

    private static final String DELETE_SESSIONS =
            "DELETE FROM spring_session WHERE principal_name = ?" + " AND primary_id <> COALESCE(?, '')";

    private final JdbcOperations jdbcOperations;

    public UserSessionInvalidator(JdbcOperations jdbcOperations) {
        Assert.notNull(jdbcOperations, "jdbcOperations cannot be null");
        this.jdbcOperations = jdbcOperations;
    }

    /**
     * 失效该主体的全部会话（keepSessionId 例外）。
     *
     * @param principalName 主体名（登录名，spring_session.principal_name 口径）
     * @param keepSessionId 保留不失效的会话 id（自助改密传当前会话；其余场景 null）
     * @return 实际删除的会话行数
     */
    public int invalidateAll(String principalName, @Nullable String keepSessionId) {
        Assert.hasText(principalName, "principalName cannot be empty");
        // 先子后父两步清：不依赖 FK 级联；中断停在中途仅留孤儿 attributes 行，无安全面
        this.jdbcOperations.update(DELETE_ATTRIBUTES, principalName, keepSessionId);
        return this.jdbcOperations.update(DELETE_SESSIONS, principalName, keepSessionId);
    }
}
