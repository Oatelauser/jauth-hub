package io.github.oatelauser.jauth.core.token.key;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.oatelauser.jauth.core.support.IntegrationTestSupport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 密钥轮转三态流转单测：90d 触发 / 14d 重叠 / ≤2 把收敛 / 过期 RETIRING 不再出网 / 线程工厂与生命周期。 时间推进用不同 Clock
 * 的固定值表达（服务无隐藏时钟依赖），种子行直插 DB 制造异常态。
 *
 * @author oatelauser
 */
class JwkRotationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");

    /** 占位密钥材料：非真实凭据，仅填充列（本测试不经 toRsaJwk 解码）。 */
    private static final String DUMMY_MATERIAL = "cGxhY2Vob2xkZXItbWF0ZXJpYWw=";

    private JdbcTemplate jdbcTemplate;

    private JdbcJwkRepository repository;

    @BeforeEach
    void setUp() {
        this.jdbcTemplate = IntegrationTestSupport.migratedJdbcTemplate("jauth-jwk-rotation");
        this.repository = new JdbcJwkRepository(this.jdbcTemplate);
    }

    @Test
    void sweepOnEmptyRepositoryCreatesActiveKey() {
        sweepAt(NOW);

        List<JwkRecord> actives = repository.findByStatusIn(JwkRecord.STATUS_ACTIVE);
        assertThat(actives).hasSize(1);
        JwkRecord key = actives.get(0);
        assertThat(key.kid()).isNotBlank();
        assertThat(key.algorithm()).isEqualTo("RS256");
        assertThat(key.keySize()).isEqualTo(2048);
        assertThat(key.createdAt()).isEqualTo(NOW);
        assertThat(key.retireAfter()).isNull();
        assertThat(key.toRsaJwk().getKeyID()).isEqualTo(key.kid());
        assertThat(key.toRsaJwk().isPrivate()).isTrue();
    }

    @Test
    void freshActiveKeyIsNotRotated() {
        sweepAt(NOW);

        sweepAt(NOW.plus(Duration.ofDays(1)));

        assertThat(repository.findByStatusIn(JwkRecord.STATUS_ACTIVE, JwkRecord.STATUS_RETIRING))
                .hasSize(1);
    }

    @Test
    void agedActiveKeyRotatesWithFourteenDayOverlap() {
        seedKey("aged-key", JwkRecord.STATUS_ACTIVE, NOW.minus(Duration.ofDays(91)), null);

        sweepAt(NOW);

        List<JwkRecord> live = repository.findByStatusIn(JwkRecord.STATUS_ACTIVE, JwkRecord.STATUS_RETIRING);
        assertThat(live).hasSize(2);
        assertThat(live.get(0).status()).isEqualTo(JwkRecord.STATUS_ACTIVE);
        assertThat(live.get(0).createdAt()).isEqualTo(NOW);
        assertThat(live.get(0).id().trim()).isNotEqualTo("aged-key");
        assertThat(live.get(1).status()).isEqualTo(JwkRecord.STATUS_RETIRING);
        assertThat(live.get(1).id().trim()).isEqualTo("aged-key");
        assertThat(live.get(1).retireAfter()).isEqualTo(NOW.plus(JwkRotationService.RETIRE_OVERLAP));
    }

    @Test
    void expiredRetiringKeyIsRetiredAndLeavesLiveSet() {
        sweepAt(NOW);
        String keyId = repository.findByStatusIn(JwkRecord.STATUS_ACTIVE).get(0).id();
        repository.updateStatus(keyId, JwkRecord.STATUS_RETIRING, NOW.minus(Duration.ofHours(1)));

        sweepAt(NOW);

        assertThat(repository.findByStatusIn(JwkRecord.STATUS_RETIRED))
                .extracting(JwkRecord::id)
                .containsExactly(keyId);
        assertThat(repository.findByStatusIn(JwkRecord.STATUS_ACTIVE, JwkRecord.STATUS_RETIRING))
                .hasSize(1);
    }

    @Test
    void convergesAnomalousExtraActiveAndRetiringKeys() {
        // 双实例竞态/中途崩溃的异常态：2 把 ACTIVE + 2 把 RETIRING
        seedKey("a-new", JwkRecord.STATUS_ACTIVE, NOW.minus(Duration.ofDays(1)), null);
        seedKey("a-old", JwkRecord.STATUS_ACTIVE, NOW.minus(Duration.ofDays(2)), null);
        seedKey("r-new", JwkRecord.STATUS_RETIRING, NOW.minus(Duration.ofDays(3)), NOW.plus(Duration.ofDays(10)));
        seedKey("r-old", JwkRecord.STATUS_RETIRING, NOW.minus(Duration.ofDays(4)), NOW.plus(Duration.ofDays(10)));

        sweepAt(NOW);

        assertThat(repository.findByStatusIn(JwkRecord.STATUS_ACTIVE))
                .extracting(key -> key.id().trim())
                .containsExactly("a-new");
        List<JwkRecord> retiring = repository.findByStatusIn(JwkRecord.STATUS_RETIRING);
        assertThat(retiring).extracting(key -> key.id().trim()).containsExactly("a-old");
        assertThat(retiring.get(0).retireAfter()).isEqualTo(NOW.plus(JwkRotationService.RETIRE_OVERLAP));
        assertThat(repository.findByStatusIn(JwkRecord.STATUS_RETIRED))
                .extracting(key -> key.id().trim())
                .containsExactlyInAnyOrder("r-new", "r-old");
    }

    @Test
    void duplicateKidInsertIsRejectedByUniqueConstraint() {
        JwkRecord record = seedKey("dup-1", JwkRecord.STATUS_ACTIVE, NOW, null);
        JwkRecord sameKid = new JwkRecord(
                "dup-2",
                record.kid(),
                record.algorithm(),
                record.keySize(),
                record.publicKey(),
                record.privateKey(),
                JwkRecord.STATUS_ACTIVE,
                NOW,
                null);
        // 轮转服务以"冲突即放弃"消费该异常（并发双实例兜底）
        assertThatThrownBy(() -> repository.save(sameKid)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rotationThreadsAreNamedDaemons() {
        Thread thread = JwkRotationService.rotationThreadFactory().newThread(() -> {});

        assertThat(thread.getName()).startsWith("jauth-jwk-rotation");
        assertThat(thread.isDaemon()).isTrue();
    }

    @Test
    void startRunsImmediateSweepAndStopIsIdempotent() throws InterruptedException {
        JwkRotationService service = new JwkRotationService(repository, fixedClock(NOW));

        service.start();
        awaitLiveKey();
        service.stop();
        service.stop();

        assertThat(repository.findByStatusIn(JwkRecord.STATUS_ACTIVE)).hasSize(1);
    }

    private void sweepAt(Instant at) {
        new JwkRotationService(repository, fixedClock(at)).sweep();
    }

    private static Clock fixedClock(Instant at) {
        return Clock.fixed(at, ZoneOffset.UTC);
    }

    /** 直插种子行制造指定状态/时间（绕过服务，用于异常态构造）。kid 取 hex 形占位，非真实凭据。 */
    private JwkRecord seedKey(String id, String status, Instant createdAt, Instant retireAfter) {
        JwkRecord record = new JwkRecord(
                id, "kid-" + id, "RS256", 2048, DUMMY_MATERIAL, DUMMY_MATERIAL, status, createdAt, retireAfter);
        repository.save(record);
        return record;
    }

    /** 轮询等待 start() 的首轮扫描落库（initialDelay=0，通常首轮即中）。 */
    private void awaitLiveKey() throws InterruptedException {
        for (int i = 0;
                i < 50 && repository.findByStatusIn(JwkRecord.STATUS_ACTIVE).isEmpty();
                i++) {
            Thread.sleep(100);
        }
    }
}
