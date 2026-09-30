package io.github.oatelauser.jauth.core.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.util.Assert;

/**
 * 内存限流器（票 07 工程项：限流内存计数器无表，SPEC §5"按用户合并限流"）。
 *
 * <p><b>固定窗口</b>（取简决策）：窗口 = 自然小时（epoch 小时对齐），窗口翻页计数清零——较滚动窗口少存
 * 每请求时间戳，误差是窗口边界处的双倍突发，对认证端点的防滥用量级足够。计数键由调用方给定
 * （{@code RateLimitFilter} 按"已认证主体 → client_id 参数 → 远端地址"取值）。
 *
 * <p><b>登录失败锁定</b>（SPEC §6：连错 N 次锁 M 分钟，限流器实现）：按用户名键控，独立于请求限流桶；
 * 成功登录清零失败计数；锁定期间判定 {@link #isLoginLocked(String)} 为真，校验路径直接拒绝。
 *
 * <p>并发模型：方法级 synchronized——计数频次是认证端点量级，简单锁吞吐富余（与 core
 * InMemoryTokenFamilyService 的取舍一致）。窗口条目与失败计数无淘汰：条目数 = 活跃键数，
 * 认证中心场景键集有限（用户/客户端/网段），不做 LRU 复杂化。
 *
 * @author oatelauser
 */
public final class RateLimiter {

    private final long limitPerWindow;

    private final int loginMaxFailures;

    private final Duration loginLockDuration;

    private final Clock clock;

    /** 窗口计数：windowStartEpochSec → (key → count)，窗口翻页整层丢弃（老窗口不可再命中）。 */
    private final Map<Long, Map<String, AtomicLong>> windows = new HashMap<>();

    /** 登录失败状态：username → 连错次数（成功清零）。 */
    private final Map<String, Integer> loginFailures = new HashMap<>();

    /** 登录锁定：username → 锁定截止时刻。 */
    private final Map<String, Instant> loginLocks = new HashMap<>();

    public RateLimiter(long limitPerWindow, int loginMaxFailures, Duration loginLockDuration, Clock clock) {
        Assert.isTrue(limitPerWindow > 0, "limitPerWindow must be positive");
        Assert.isTrue(loginMaxFailures > 0, "loginMaxFailures must be positive");
        Assert.notNull(loginLockDuration, "loginLockDuration cannot be null");
        Assert.isTrue(!loginLockDuration.isNegative(), "loginLockDuration must not be negative");
        Assert.notNull(clock, "clock cannot be null");
        this.limitPerWindow = limitPerWindow;
        this.loginMaxFailures = loginMaxFailures;
        this.loginLockDuration = loginLockDuration;
        this.clock = clock;
    }

    /**
     * 消费一次配额（固定窗口递增）。
     *
     * @param key 合并桶键（主体/客户端/地址，由调用方定）
     * @return 本次消费结果（含响应三头所需的 limit/remaining/reset）
     */
    public synchronized Consumption consume(String key) {
        Assert.hasText(key, "key cannot be empty");
        long now = clock.instant().getEpochSecond();
        long windowStart = now / 3600 * 3600;
        long windowEnd = windowStart + 3600;
        Map<String, AtomicLong> window = windows.computeIfAbsent(windowStart, start -> new HashMap<>());
        // 窗口翻页：先丢老窗口再计数，保证 windows 最多存两个窗口（跨页读的并发窗口）
        windows.keySet().retainAll(java.util.Set.of(windowStart));
        long count = window.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
        long remaining = Math.max(0, limitPerWindow - count);
        return new Consumption(count <= limitPerWindow, limitPerWindow, remaining, windowEnd - now);
    }

    /**
     * 登录失败登记：连错达阈值即锁 {@code loginLockDuration}。
     *
     * @param username 登录用户名
     */
    public synchronized void onLoginFailure(String username) {
        Assert.hasText(username, "username cannot be empty");
        int failures = loginFailures.merge(username, 1, Integer::sum);
        if (failures >= loginMaxFailures) {
            loginLocks.put(username, clock.instant().plus(loginLockDuration));
            loginFailures.remove(username);
        }
    }

    /**
     * 登录成功：失败计数清零（锁定不会因成功而提前解除——锁定期内校验路径先拒，本方法不应被触达）。
     *
     * @param username 登录用户名
     */
    public synchronized void onLoginSuccess(String username) {
        Assert.hasText(username, "username cannot be empty");
        loginFailures.remove(username);
    }

    /**
     * 登录锁定判定（到期即视为未锁，锁条目惰性清除）。
     *
     * @param username 登录用户名
     * @return true 表示锁定期内，应直接拒绝
     */
    public synchronized boolean isLoginLocked(String username) {
        Assert.hasText(username, "username cannot be empty");
        Instant lockedUntil = loginLocks.get(username);
        if (lockedUntil == null) {
            return false;
        }
        if (!clock.instant().isBefore(lockedUntil)) {
            // 到点即解（锁定区间为 [lockAt, lockedUntil)，满 15 分钟整点可再试）
            loginLocks.remove(username);
            return false;
        }
        return true;
    }

    /** 一次配额消费结果：X-RateLimit-Limit/Remaining/Reset 三头的数据源。 */
    public record Consumption(boolean allowed, long limit, long remaining, long resetAfterSeconds) {}
}
