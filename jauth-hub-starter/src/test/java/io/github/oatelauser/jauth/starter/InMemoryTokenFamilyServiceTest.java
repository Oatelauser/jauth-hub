package io.github.oatelauser.jauth.starter;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oatelauser.jauth.core.token.InMemoryTokenFamilyService;
import io.github.oatelauser.jauth.core.token.TokenHash;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * core 内存族谱存储的直接单测（B4 补的 core 类；测试放 starter 侧以兑现"本批 core 只加一个主类"的约束，
 * 语义与 JDBC 版族谱一致）。
 *
 * @author oatelauser
 */
class InMemoryTokenFamilyServiceTest {

    private final InMemoryTokenFamilyService service = new InMemoryTokenFamilyService();

    @Test
    @DisplayName("轮转记族：同哈希幂等，代际递增，旧 ACTIVE 降 SUPERSEDED")
    void recordAndSupersede() {
        service.recordRefreshToken("alice", "client-1", hash("r1"));
        service.recordRefreshToken("alice", "client-1", hash("r1")); // 幂等重放记录
        service.recordRefreshToken("alice", "client-1", hash("r2"));

        assertThat(service.findByRefreshTokenHash(hash("r1")).orElseThrow().status())
                .isEqualTo(InMemoryTokenFamilyService.STATUS_SUPERSEDED);
        assertThat(service.findByRefreshTokenHash(hash("r2")).orElseThrow().status())
                .isEqualTo(InMemoryTokenFamilyService.STATUS_ACTIVE);
        assertThat(service.findByRefreshTokenHash(hash("r2")).orElseThrow().generation())
                .isEqualTo(2);
        assertThat(service.findByRefreshTokenHash(hash("never"))).isEmpty();
    }

    @Test
    @DisplayName("族隔离：不同 client 各记各代，互不 supersede")
    void familiesAreIsolated() {
        service.recordRefreshToken("alice", "client-1", hash("r1"));
        service.recordRefreshToken("alice", "client-2", hash("r2"));

        assertThat(service.findByRefreshTokenHash(hash("r1")).orElseThrow().status())
                .isEqualTo(InMemoryTokenFamilyService.STATUS_ACTIVE);
        assertThat(service.findByRefreshTokenHash(hash("r2")).orElseThrow().generation())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("烧族：本族全 BURNED（含历史代），他族不受扰")
    void burnFamilyMarksOnlyOwnRows() {
        service.recordRefreshToken("alice", "client-1", hash("r1"));
        service.recordRefreshToken("alice", "client-1", hash("r2"));
        service.recordRefreshToken("bob", "client-1", hash("r3"));

        int burned = service.burnFamily("alice", "client-1");

        assertThat(burned).isEqualTo(2);
        assertThat(service.findByRefreshTokenHash(hash("r1")).orElseThrow().status())
                .isEqualTo(InMemoryTokenFamilyService.STATUS_BURNED);
        assertThat(service.findByRefreshTokenHash(hash("r2")).orElseThrow().status())
                .isEqualTo(InMemoryTokenFamilyService.STATUS_BURNED);
        assertThat(service.findByRefreshTokenHash(hash("r3")).orElseThrow().status())
                .isEqualTo(InMemoryTokenFamilyService.STATUS_ACTIVE);
    }

    private static String hash(String token) {
        return TokenHash.sha256Hex(token);
    }
}
