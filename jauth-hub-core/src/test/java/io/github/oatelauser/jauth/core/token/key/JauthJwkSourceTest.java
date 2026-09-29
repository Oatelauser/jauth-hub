package io.github.oatelauser.jauth.core.token.key;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.KeyType;
import io.github.oatelauser.jauth.core.support.IntegrationTestSupport;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JWK 源双消费面单测：JWKS 发布面（permissive matcher）得全部存活钥且新钥在前；签名面（privateOnly matcher，NimbusJwtEncoder
 * 语义）只得最新 ACTIVE——多把 RS256 共存时编码器默认拒签，这是轮转重叠期能继续签名的关键。
 *
 * @author oatelauser
 */
class JauthJwkSourceTest {

    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");

    private JdbcJwkRepository repository;

    private JauthJwkSource jwkSource;

    /** 真实 RSA 材料一份，多行复用（源只做转换，材料相同不影响选择逻辑）。 */
    private String publicKeyMaterial;

    private String privateKeyMaterial;

    @BeforeEach
    void setUp() throws NoSuchAlgorithmException {
        JdbcTemplate jdbcTemplate = IntegrationTestSupport.migratedJdbcTemplate("jauth-jwk-source");
        this.repository = new JdbcJwkRepository(jdbcTemplate);
        this.jwkSource = new JauthJwkSource(repository);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        this.publicKeyMaterial =
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        this.privateKeyMaterial =
                Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
    }

    @Test
    void jwksSelectionReturnsAllLiveKeysNewestActiveFirst() {
        seedKey("retiring", JwkRecord.STATUS_RETIRING, NOW.minus(Duration.ofDays(1)));
        seedKey("active", JwkRecord.STATUS_ACTIVE, NOW);

        List<JWK> published = jwkSource.get(new JWKSelector(permissiveMatcher()), null);

        assertThat(published).hasSize(2);
        assertThat(published.get(0).getKeyID()).isEqualTo("kid-active");
        assertThat(published.get(1).getKeyID()).isEqualTo("kid-retiring");
    }

    @Test
    void signingSelectionGetsSingleNewestActiveKey() {
        seedKey("retiring", JwkRecord.STATUS_RETIRING, NOW.minus(Duration.ofDays(1)));
        seedKey("active", JwkRecord.STATUS_ACTIVE, NOW);

        List<JWK> signing = jwkSource.get(new JWKSelector(signingMatcher()), null);

        assertThat(signing).hasSize(1);
        assertThat(signing.get(0).getKeyID()).isEqualTo("kid-active");
    }

    @Test
    void retiredKeysAreNeverServed() {
        seedKey("retired", JwkRecord.STATUS_RETIRED, NOW);

        assertThat(jwkSource.get(new JWKSelector(permissiveMatcher()), null)).isEmpty();
        assertThat(jwkSource.get(new JWKSelector(signingMatcher()), null)).isEmpty();
    }

    @Test
    void publishedKeysAreCappedAtTwo() {
        // 多实例竞态超发（3 把存活）时读取侧仍守住 §6 的 ≤2 基线
        seedKey("oldest-retiring", JwkRecord.STATUS_RETIRING, NOW.minus(Duration.ofDays(3)));
        seedKey("retiring", JwkRecord.STATUS_RETIRING, NOW.minus(Duration.ofDays(1)));
        seedKey("active", JwkRecord.STATUS_ACTIVE, NOW);

        List<JWK> published = jwkSource.get(new JWKSelector(permissiveMatcher()), null);

        assertThat(published).hasSize(JauthJwkSource.MAX_LIVE_KEYS);
        assertThat(published.get(0).getKeyID()).isEqualTo("kid-active");
        assertThat(published.get(1).getKeyID()).isEqualTo("kid-retiring");
    }

    /** JWKS 端点（NimbusJwkSetEndpointFilter）的宽松匹配：全量命中。 */
    private static JWKMatcher permissiveMatcher() {
        return new JWKMatcher.Builder().build();
    }

    /** NimbusJwtEncoder 的签名匹配：按算法 + 只要私钥钥（privateOnly）。 */
    private static JWKMatcher signingMatcher() {
        return new JWKMatcher.Builder()
                .keyType(KeyType.forAlgorithm(JWSAlgorithm.RS256))
                .algorithms(JWSAlgorithm.RS256)
                .privateOnly(true)
                .build();
    }

    private void seedKey(String id, String status, Instant createdAt) {
        repository.save(new JwkRecord(
                id, "kid-" + id, "RS256", 2048, publicKeyMaterial, privateKeyMaterial, status, createdAt, null));
    }
}
