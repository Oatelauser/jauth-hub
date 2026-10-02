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

    @Test
    @DisplayName("授权 id 索引：record 记、discard 撤、burnAllByPrincipal 清（D1 清剿枚举面）")
    void authorizationIdIndexTracksRecordAndDiscard() {
        service.recordAuthorizationId("alice", "auth-1");
        service.recordAuthorizationId("alice", "auth-2");
        service.recordAuthorizationId("alice", "auth-1"); // 幂等
        service.recordAuthorizationId("bob", "auth-3");

        assertThat(service.authorizationIdsByPrincipal("alice")).containsExactlyInAnyOrder("auth-1", "auth-2");
        assertThat(service.authorizationIdsByPrincipal("bob")).containsExactly("auth-3");
        assertThat(service.authorizationIdsByPrincipal("carol")).isEmpty();

        service.discardAuthorizationId("alice", "auth-1");
        service.discardAuthorizationId("alice", "never-seen"); // 缺席无害

        assertThat(service.authorizationIdsByPrincipal("alice")).containsExactly("auth-2");
    }

    @Test
    @DisplayName("按主体烧断：该主体全部族 BURNED 且清索引，他人族不受扰")
    void burnAllByPrincipalBurnsEveryFamilyAndClearsIndex() {
        service.recordRefreshToken("alice", "client-1", hash("r1"));
        service.recordRefreshToken("alice", "client-2", hash("r2"));
        service.recordRefreshToken("bob", "client-1", hash("r3"));
        service.recordAuthorizationId("alice", "auth-1");

        int burned = service.burnAllByPrincipal("alice");

        assertThat(burned).isEqualTo(2);
        assertThat(service.findByRefreshTokenHash(hash("r1")).orElseThrow().status())
                .isEqualTo(InMemoryTokenFamilyService.STATUS_BURNED);
        assertThat(service.findByRefreshTokenHash(hash("r2")).orElseThrow().status())
                .isEqualTo(InMemoryTokenFamilyService.STATUS_BURNED);
        assertThat(service.findByRefreshTokenHash(hash("r3")).orElseThrow().status())
                .isEqualTo(InMemoryTokenFamilyService.STATUS_ACTIVE);
        assertThat(service.authorizationIdsByPrincipal("alice")).isEmpty();
    }

    private static String hash(String token) {
        return TokenHash.sha256Hex(token);
    }
}
