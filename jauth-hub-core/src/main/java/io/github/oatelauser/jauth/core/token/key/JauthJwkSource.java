package io.github.oatelauser.jauth.core.token.key;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.util.List;

/**
 * DB 化 JWK 源（表 jauth_jwk）：同一实例同时服务签名（NimbusJwtEncoder）与 JWKS 发布
 * （NimbusJwkSetEndpointFilter）两个消费面，按调用方 matcher 的语义分流：
 *
 * <ul>
 *   <li><b>发布/验签面</b>（permissive matcher）：返回全部存活钥（ACTIVE+RETIRING）——RETIRING 重叠期内 JWKS 仍可验旧令牌（SPEC
 *       §6）。私钥部分由 nimbus 序列化时剥离，出网只有公钥。
 *   <li><b>签名面</b>（matcher 带 privateOnly）：NimbusJwtEncoder 对"同算法多把钥"默认直接拒签 （Failed to select a key
 *       since there are multiple），故只给最新一把 ACTIVE——签名永远取新钥。
 * </ul>
 *
 * <p>返回键序列固定为"最新 ACTIVE 在前"（仓储按 created_at 降序 + 收敛后至多一 ACTIVE 一 RETIRING）， 读取侧再以 {@link
 * #MAX_LIVE_KEYS} 截断——多实例轮转竞态超发时，出网集合仍守住 §6 的"最多 2 把"。
 *
 * <p>每次调用都查库：签名/JWKS 的 QPS 下可接受（索引窄表）；如成为热点，升级路径为短 TTL 本地缓存， 语义不变（JWKS 消费方自带缓存，本源无需长缓存）。
 *
 * @author oatelauser
 */
public class JauthJwkSource implements JWKSource<SecurityContext> {

    /** 出网钥上限（§6：ACTIVE + RETIRING ≤ 2）。 */
    static final int MAX_LIVE_KEYS = 2;

    private final JdbcJwkRepository repository;

    public JauthJwkSource(JdbcJwkRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<JWK> get(JWKSelector jwkSelector, SecurityContext context) {
        List<JWK> matches = jwkSelector.select(new JWKSet(liveKeys()));
        if (isSigningSelection(jwkSelector) && matches.size() > 1) {
            return List.of(matches.get(0));
        }
        return matches;
    }

    private List<JWK> liveKeys() {
        return repository.findByStatusIn(JwkRecord.STATUS_ACTIVE, JwkRecord.STATUS_RETIRING).stream()
                .limit(MAX_LIVE_KEYS)
                .<JWK>map(JwkRecord::toRsaJwk)
                .toList();
    }

    /**
     * 识别签名面调用：NimbusJwtEncoder 的 matcher 带 privateOnly=true（JWKS 发布侧的 permissive matcher 两个开关皆
     * false）。这是编码器/过滤器的公开行为而非巧合约定，升级框架时由轮转单测拦截漂移。
     */
    private static boolean isSigningSelection(JWKSelector jwkSelector) {
        return jwkSelector.getMatcher() != null && jwkSelector.getMatcher().isPrivateOnly();
    }
}
