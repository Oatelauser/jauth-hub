package io.github.oatelauser.jauth.core.token.key;

import io.github.oatelauser.jauth.core.util.UuidV7;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;

/**
 * 签名密钥轮转调度（SPEC §6：90d 轮转 + 旧钥保留 14d + 最多 2 把共存，DB 化供多实例共享）。
 *
 * <p>扫描任务做三件事（顺序有意为之，见各方法注释）：过期 RETIRING 转 RETIRED 不再出网； 收敛不变量（多实例竞态可能造成多把 ACTIVE / 多把
 * RETIRING，收敛到最新 ACTIVE + 至多一把 RETIRING）； 最新 ACTIVE 满 90d（或不存在）则生成新钥并把旧 ACTIVE 转
 * RETIRING（retire_after = 当时 + 14d）。
 *
 * <p><b>并发兜底</b>：写入幂等。新钥 kid 为 UUID v7，常态下两实例不会撞 kid；撞唯一约束 （{@link
 * DuplicateKeyException}）即视为对端实例已完成同代轮转，放弃本轮——两实例同时扫到的场景由 下一轮收敛不变量修复，任一时刻出网钥数由 {@link
 * JauthJwkSource} 的读取上限兜底。
 *
 * <p><b>生命周期</b>：调度用单线程 {@link ScheduledExecutorService}（禁 Timer/裸线程，p3c 并发章）， 线程为守护线程、命名
 * jauth-jwk-rotation。start()/stop() 由 B4 装配挂钩（SmartLifecycle 等）调用， stop
 * 不等待在途任务——轮转无副作用窗口要求（未完成的本轮由重启后首轮扫描补齐）。
 *
 * @author oatelauser
 */
public class JwkRotationService {

    /** 轮转周期：最新 ACTIVE 满 90d 即换新钥。 */
    static final Duration ROTATION_PERIOD = Duration.ofDays(90);

    /** 重叠窗口：旧钥转 RETIRING 后再出网 14d（旧令牌的验签缓冲）。 */
    static final Duration RETIRE_OVERLAP = Duration.ofDays(14);

    /** RSA 密钥位长。 */
    static final int KEY_SIZE_BITS = 2048;

    private static final Logger log = LoggerFactory.getLogger(JwkRotationService.class);

    /** 扫描周期：远小于轮转粒度即可，失之毫秒无碍（到期判定在扫描时点）。 */
    private static final Duration SWEEP_INTERVAL = Duration.ofHours(1);

    private static final String THREAD_NAME_PREFIX = "jauth-jwk-rotation";

    private final JdbcJwkRepository repository;

    private final Clock clock;

    /** start/stop 同步：调度器句柄只在这两个方法里读写。 */
    private ScheduledExecutorService scheduler;

    public JwkRotationService(JdbcJwkRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** 启动调度（幂等）。initialDelay=0：启动即扫一轮，新部署/重启后欠账轮转立刻补上。 */
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(rotationThreadFactory());
        scheduler.scheduleWithFixedDelay(this::sweepSafely, 0, SWEEP_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** 停止调度（幂等）。不等待在途任务（见类注释）。 */
    public synchronized void stop() {
        if (scheduler == null) {
            return;
        }
        scheduler.shutdownNow();
        scheduler = null;
    }

    /** 轮转线程工厂：命名 jauth-jwk-rotation-N、守护线程（不阻 JVM 退出，密钥轮转可被重启补齐）。 */
    static ThreadFactory rotationThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME_PREFIX + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** 定时任务的异常护栏：scheduleWithFixedDelay 的任务抛出未捕获异常即被静默取消， 故包一层记日志后放行下一轮（轮转欠账由后续扫描补齐，而非永久停摆）。 */
    private void sweepSafely() {
        try {
            sweep();
        } catch (RuntimeException ex) {
            log.error("JWK rotation sweep failed; next sweep will retry", ex);
        }
    }

    /** 执行一轮扫描（包内可见：单测直调，绕过调度器）。 */
    void sweep() {
        Instant now = clock.instant();
        repository.retireExpiredRetiring(now);
        normalizeInvariants(now);
        rotateIfDue(now);
    }

    /**
     * 收敛不变量：最新 ACTIVE 之外的 ACTIVE 降 RETIRING（14d 出网缓冲）， 最新 RETIRING 之外的 RETIRING 直接 RETIRED——守住"任一时刻
     * ≤ 2 把"的 §6 基线。 只在异常态（双实例竞态 / 轮转中途崩溃）有多余行可收敛，常态是空操作。
     */
    private void normalizeInvariants(Instant now) {
        List<JwkRecord> live = repository.findByStatusIn(JwkRecord.STATUS_ACTIVE);
        for (int i = 1; i < live.size(); i++) {
            repository.updateStatus(live.get(i).id(), JwkRecord.STATUS_RETIRING, now.plus(RETIRE_OVERLAP));
        }
        List<JwkRecord> retiring = repository.findByStatusIn(JwkRecord.STATUS_RETIRING);
        for (int i = 1; i < retiring.size(); i++) {
            repository.updateStatus(retiring.get(i).id(), JwkRecord.STATUS_RETIRED, null);
        }
    }

    /**
     * 到期轮转：无 ACTIVE 或最新 ACTIVE 已满 90d 时换钥。先插新 ACTIVE 再降旧 ACTIVE—— 中途崩溃的窗口是"两把 ACTIVE"（下轮收敛），好过"零把
     * ACTIVE"（签名中断）。
     */
    private void rotateIfDue(Instant now) {
        List<JwkRecord> actives = repository.findByStatusIn(JwkRecord.STATUS_ACTIVE);
        if (!actives.isEmpty() && actives.get(0).createdAt().isAfter(now.minus(ROTATION_PERIOD))) {
            return;
        }
        JwkRecord fresh = generateKey(now);
        try {
            repository.save(fresh);
        } catch (DuplicateKeyException ex) {
            // 并发实例同代轮转撞 kid：对端已写入，放弃本轮（收敛不变量下轮修复）
            log.info("Concurrent JWK rotation detected (kid conflict), skipping this round");
            return;
        }
        for (JwkRecord oldActive : actives) {
            repository.updateStatus(oldActive.id(), JwkRecord.STATUS_RETIRING, now.plus(RETIRE_OVERLAP));
        }
        log.info("Rotated signing key: new kid={} active, {} old key(s) retiring", fresh.kid(), actives.size());
    }

    /** 生成新钥：JDK KeyPairGenerator（RSA 2048，默认 SecureRandom），kid 为 UUID v7。 */
    private JwkRecord generateKey(Instant now) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(KEY_SIZE_BITS);
            KeyPair keyPair = generator.generateKeyPair();
            return new JwkRecord(
                    UuidV7.generate().toString(),
                    nextKid(),
                    "RS256",
                    KEY_SIZE_BITS,
                    Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                    Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()),
                    JwkRecord.STATUS_ACTIVE,
                    now,
                    null);
        } catch (NoSuchAlgorithmException ex) {
            // JVM 规范强制提供 RSA，走到这里说明运行时残缺
            throw new IllegalStateException("RSA algorithm is not available in this JVM", ex);
        }
    }

    private static String nextKid() {
        return UuidV7.generate().toString();
    }
}
