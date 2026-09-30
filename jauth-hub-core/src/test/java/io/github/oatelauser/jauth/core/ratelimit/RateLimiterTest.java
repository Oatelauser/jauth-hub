package io.github.oatelauser.jauth.core.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link RateLimiter} 单元测试（SPEC §6 默认策略表的可配置语义）：限额触发、窗口翻页重置、登录连错
 * 锁定与到期解除——全部以可变钟推进，不真实等待。
 *
 * @author oatelauser
 */
class RateLimiterTest {

    private static final Instant START = Instant.parse("2026-09-29T10:15:00Z");

    private final MutableClock clock = new MutableClock(START);

    @Test
    @DisplayName("限额触发：窗口内第 N+1 次拒绝，Remaining 递减到 0，Reset 为窗口剩余秒数")
    void limitTriggersWithinWindow() {
        RateLimiter limiter = new RateLimiter(3, 5, Duration.ofMinutes(15), clock);

        RateLimiter.Consumption first = limiter.consume("caller");
        assertThat(first.allowed()).isTrue();
        assertThat(first.limit()).isEqualTo(3);
        assertThat(first.remaining()).isEqualTo(2);
        // 10:15 所在小时窗口 10:00–11:00，剩余 45 分钟 = 2700 秒
        assertThat(first.resetAfterSeconds()).isEqualTo(2700);

        assertThat(limiter.consume("caller").allowed()).isTrue();
        assertThat(limiter.consume("caller").remaining()).isZero();
        RateLimiter.Consumption over = limiter.consume("caller");
        assertThat(over.allowed()).isFalse();
        assertThat(over.remaining()).isZero();
    }

    @Test
    @DisplayName("窗口重置：翻页后同一键计数清零；不同键互不干扰（合并桶语义）")
    void windowResetsAndKeysAreIndependent() {
        RateLimiter limiter = new RateLimiter(1, 5, Duration.ofMinutes(15), clock);
        assertThat(limiter.consume("a").allowed()).isTrue();
        assertThat(limiter.consume("a").allowed()).isFalse();
        assertThat(limiter.consume("b").allowed()).isTrue();

        clock.advance(Duration.ofMinutes(45));
        assertThat(limiter.consume("a").allowed()).as("翻入新窗口后配额恢复").isTrue();
    }

    @Test
    @DisplayName("登录锁定：连错 5 次锁 15 分钟，锁定期内拒绝；到期自动解除；成功登录清零计数")
    void loginLockoutSemantics() {
        RateLimiter limiter = new RateLimiter(5000, 5, Duration.ofMinutes(15), clock);

        for (int i = 0; i < 4; i++) {
            limiter.onLoginFailure("alice");
        }
        assertThat(limiter.isLoginLocked("alice")).as("未达阈值不锁").isFalse();
        // 成功一次即清零（第 5 次连错不该发生）
        limiter.onLoginSuccess("alice");
        for (int i = 0; i < 4; i++) {
            limiter.onLoginFailure("alice");
        }
        assertThat(limiter.isLoginLocked("alice")).isFalse();

        limiter.onLoginFailure("alice");
        assertThat(limiter.isLoginLocked("alice")).as("连错第 5 次即锁").isTrue();

        clock.advance(Duration.ofMinutes(14));
        assertThat(limiter.isLoginLocked("alice")).as("锁定期内保持").isTrue();
        clock.advance(Duration.ofMinutes(1));
        assertThat(limiter.isLoginLocked("alice")).as("满 15 分钟解除").isFalse();

        assertThat(limiter.isLoginLocked("bob")).as("锁定按用户名键控，不殃及他人").isFalse();
    }

    /** 可变测试钟（限流/锁定的窗口与到期推进）。 */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            this.now = this.now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.now;
        }
    }
}
