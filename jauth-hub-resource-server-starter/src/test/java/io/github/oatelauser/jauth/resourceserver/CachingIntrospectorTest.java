package io.github.oatelauser.jauth.resourceserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionAuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;

/**
 * 缓存装饰器行为面（脚本化 delegate + 可推进时钟）：正/负命中、TTL 过期回源、容量护栏 LRU 淘汰、
 * 端点故障不缓存（区别于 active=false 的确定性判定）。
 *
 * @author oatelauser
 */
class CachingIntrospectorTest {

    private final MutableClock clock = new MutableClock();

    private final RecordingDelegate delegate = new RecordingDelegate();

    private CachingIntrospector introspector(Duration ttl, Duration negativeTtl, int maxEntries) {
        return new CachingIntrospector(this.delegate, ttl, negativeTtl, maxEntries, this.clock);
    }

    @Test
    @DisplayName("正缓存命中：同令牌两次内省只回源一次，返回同一 principal")
    void positiveCacheHit() {
        CachingIntrospector introspector = this.introspector(Duration.ofSeconds(30), Duration.ofSeconds(30), 1000);

        OAuth2AuthenticatedPrincipal first = introspector.introspect("token-A");
        OAuth2AuthenticatedPrincipal second = introspector.introspect("token-A");

        assertThat(second).isSameAs(first);
        assertThat(this.delegate.callCount("token-A")).isEqualTo(1);
    }

    @Test
    @DisplayName("负缓存命中：inactive 两次内省只回源一次，两次都按框架语义抛异常")
    void negativeCacheHit() {
        CachingIntrospector introspector = this.introspector(Duration.ofSeconds(30), Duration.ofSeconds(30), 1000);
        this.delegate.inactiveTokens.add("garbage-token");

        assertThatThrownBy(() -> introspector.introspect("garbage-token"))
                .isInstanceOf(OAuth2IntrospectionException.class);
        assertThatThrownBy(() -> introspector.introspect("garbage-token"))
                .isInstanceOf(OAuth2IntrospectionException.class);
        assertThat(this.delegate.callCount("garbage-token")).isEqualTo(1);
    }

    @Test
    @DisplayName("正缓存 TTL 过期：30s 后回源重查（撤销时延上限即 TTL）")
    void positiveTtlExpiry() {
        CachingIntrospector introspector = this.introspector(Duration.ofSeconds(30), Duration.ofSeconds(30), 1000);

        introspector.introspect("token-A");
        this.clock.advance(Duration.ofSeconds(30));
        introspector.introspect("token-A");

        assertThat(this.delegate.callCount("token-A")).isEqualTo(2);
    }

    @Test
    @DisplayName("负缓存 TTL 过期：negative-ttl 到点后允许重新回源（令牌可能已转正）")
    void negativeTtlExpiry() {
        CachingIntrospector introspector = this.introspector(Duration.ofSeconds(30), Duration.ofSeconds(5), 1000);
        this.delegate.inactiveTokens.add("token-N");

        assertThatThrownBy(() -> introspector.introspect("token-N"));
        this.clock.advance(Duration.ofSeconds(6));
        this.delegate.inactiveTokens.clear();
        assertThat(introspector.introspect("token-N")).isNotNull();

        assertThat(this.delegate.callCount("token-N")).isEqualTo(2);
    }

    @Test
    @DisplayName("容量护栏：max-entries=2，第三令牌挤掉 LRU 项，被淘汰者回源重查")
    void capacityGuardEvictsLeastRecentlyUsed() {
        CachingIntrospector introspector = this.introspector(Duration.ofSeconds(30), Duration.ofSeconds(30), 2);

        introspector.introspect("token-1");
        introspector.introspect("token-2");
        introspector.introspect("token-1"); // touch token-1：token-2 成为 LRU
        introspector.introspect("token-3"); // 挤掉 token-2
        introspector.introspect("token-2"); // 回源重查

        assertThat(this.delegate.callCount("token-1")).isEqualTo(1);
        assertThat(this.delegate.callCount("token-2")).isEqualTo(2);
        assertThat(this.delegate.callCount("token-3")).isEqualTo(1);
    }

    @Test
    @DisplayName("端点故障不缓存：故障后的下一次调用正常回源并成功")
    void endpointFailureIsNotCached() {
        CachingIntrospector introspector = this.introspector(Duration.ofSeconds(30), Duration.ofSeconds(30), 1000);
        this.delegate.failure = new OAuth2IntrospectionException("endpoint down");

        assertThatThrownBy(() -> introspector.introspect("token-R"));
        this.delegate.failure = null;
        assertThat(introspector.introspect("token-R")).isNotNull();

        assertThat(this.delegate.callCount("token-R")).isEqualTo(2);
    }

    /**
     * 可手动推进的时钟（TTL 语义确定性测试，不实等墙钟）。
     */
    private static final class MutableClock extends Clock {

        private final AtomicLong millis = new AtomicLong();

        void advance(Duration duration) {
            this.millis.addAndGet(duration.toMillis());
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(this.millis.get());
        }
    }

    /**
     * 脚本化内省器：按 token 计数回源次数，可标记 inactive 或注入一次性故障。
     */
    private static final class RecordingDelegate implements OpaqueTokenIntrospector {

        private final Map<String, Integer> calls = new HashMap<>();

        private final Set<String> inactiveTokens = new HashSet<>();

        @Nullable
        private volatile RuntimeException failure;

        @Override
        public OAuth2AuthenticatedPrincipal introspect(String token) {
            this.calls.merge(token, 1, Integer::sum);
            RuntimeException currentFailure = this.failure;
            if (currentFailure != null) {
                throw currentFailure;
            }
            if (this.inactiveTokens.contains(token)) {
                throw new JauthOpaqueTokenIntrospector.InactiveOAuth2TokenException("Token is not active");
            }
            return new OAuth2IntrospectionAuthenticatedPrincipal(token, Map.of("sub", token), List.of());
        }

        int callCount(String token) {
            return this.calls.getOrDefault(token, 0);
        }
    }
}
