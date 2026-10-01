package io.github.oatelauser.jauth.core.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * sudo 新鲜度判官单测（v1.2 C3）：从未强认证（NULL）/用户不在池 fail-closed；TTL 边界——打点 + TTL
 * 恰好落在现在即过期（isAfter 严格比较），早一秒仍新鲜。凭据为占位常量。
 *
 * @author oatelauser
 */
class SudoGateTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");

    private static final String ALICE_ID = "018f0000-0000-7000-8000-0000000000bb";

    private final InMemoryUserRepository users = new InMemoryUserRepository();

    private final SudoGate gate = new SudoGate(this.users, Duration.ofMinutes(15), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("从未强认证（NULL）与用户不在池都判不新鲜（fail-closed，拦截层无需再区分）")
    void nullStrongAuthAndUnknownUserAreStale() {
        this.users.save(new JauthUser(
                ALICE_ID,
                "alice",
                "{bcrypt}placeholder-not-a-real-hash",
                null,
                null,
                JauthUser.ROLE_USER,
                JauthUser.STATUS_ACTIVE,
                null,
                NOW.minusSeconds(60)));
        assertThat(this.gate.isFresh("alice")).isFalse();
        assertThat(this.gate.isFresh("no-such-user")).isFalse();
    }

    @Test
    @DisplayName("TTL 边界：打点+TTL=现在 → 过期；早一秒 → 新鲜")
    void ttlBoundary() {
        this.users.save(new JauthUser(
                ALICE_ID,
                "alice",
                "{bcrypt}placeholder-not-a-real-hash",
                null,
                null,
                JauthUser.ROLE_USER,
                JauthUser.STATUS_ACTIVE,
                null,
                NOW.minusSeconds(60)));
        this.users.updateStrongAuthAt(ALICE_ID, NOW.minus(Duration.ofMinutes(15)));
        assertThat(this.gate.isFresh("alice")).isFalse();
        this.users.updateStrongAuthAt(
                ALICE_ID, NOW.minus(Duration.ofMinutes(15)).plusSeconds(1));
        assertThat(this.gate.isFresh("alice")).isTrue();
    }
}
