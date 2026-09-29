package io.github.oatelauser.jauth.starter;

import io.github.oatelauser.jauth.core.token.InMemoryTokenFamilyService;
import io.github.oatelauser.jauth.core.token.TokenHash;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.util.Assert;

/**
 * memory 模式授权服务：框架 {@link InMemoryOAuth2AuthorizationService}（final，不可子类化）的委托包装，
 * 补上 JDBC 版（core 的 JauthJdbcOAuth2AuthorizationService）已有的 RTR 族谱熔断——save 记族、refresh 未命中探族、
 * 命中即烧族返回 null（框架报 invalid_grant）。
 *
 * <p>与 JDBC 版的两点语义差异（memory 模式既定边界，SPEC §1 存储决议）：令牌值明文在内存（无哈希手术，族谱侧仍只存
 * SHA-256）；烧族删除授权行靠自维护的 (principal, client) → 授权 id 索引（框架内存实现不暴露按主体枚举）。
 *
 * <p>置于 starter 而非 core：core 的内存实现缺口在 B4 装配时才显形，本批向 core 只补族谱存储一个类（任务约束），
 * 授权服务包装归装配层；若后续想把内存路径整体下沉 core，本类与 FamilyAware 前缀一并迁移即可。
 *
 * @author oatelauser
 */
final class FamilyAwareInMemoryAuthorizationService implements OAuth2AuthorizationService {

    private final InMemoryOAuth2AuthorizationService delegate;

    private final InMemoryTokenFamilyService tokenFamilyService;

    /** 烧族索引：principalName + registeredClientId → 授权 id 集。save 记录、remove 清理。 */
    private final Map<String, Set<String>> authorizationIdsByFamily = new ConcurrentHashMap<>();

    FamilyAwareInMemoryAuthorizationService(
            InMemoryOAuth2AuthorizationService delegate, InMemoryTokenFamilyService tokenFamilyService) {
        Assert.notNull(delegate, "delegate cannot be null");
        Assert.notNull(tokenFamilyService, "tokenFamilyService cannot be null");
        this.delegate = delegate;
        this.tokenFamilyService = tokenFamilyService;
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        Assert.notNull(authorization, "authorization cannot be null");
        delegate.save(authorization);
        authorizationIdsByFamily
                .computeIfAbsent(familyKey(authorization), key -> ConcurrentHashMap.newKeySet())
                .add(authorization.getId());
        recordRefreshTokenFamily(authorization);
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        Assert.notNull(authorization, "authorization cannot be null");
        delegate.remove(authorization);
        Set<String> ids = authorizationIdsByFamily.get(familyKey(authorization));
        if (ids != null) {
            ids.remove(authorization.getId());
        }
    }

    @Override
    public @Nullable OAuth2Authorization findById(String id) {
        Assert.hasText(id, "id cannot be empty");
        return delegate.findById(id);
    }

    @Override
    public @Nullable OAuth2Authorization findByToken(String token, @Nullable OAuth2TokenType tokenType) {
        Assert.hasText(token, "token cannot be empty");
        OAuth2Authorization found = delegate.findByToken(token, tokenType);
        if (found != null) {
            return found;
        }
        // refresh 未命中（或内省/吊销全类型未命中）→ 族谱探测：历史哈希命中即烧族
        if (tokenType == null || OAuth2TokenType.REFRESH_TOKEN.equals(tokenType)) {
            burnFamilyIfReplayed(token);
        }
        return null;
    }

    /** save 路径族谱记录：授权带 refresh token 即记入（同哈希幂等跳过，族谱存储保证）。 */
    private void recordRefreshTokenFamily(OAuth2Authorization authorization) {
        OAuth2Authorization.Token<OAuth2RefreshToken> refreshToken = authorization.getRefreshToken();
        if (refreshToken == null || refreshToken.getToken().getTokenValue() == null) {
            return;
        }
        tokenFamilyService.recordRefreshToken(
                authorization.getPrincipalName(),
                authorization.getRegisteredClientId(),
                TokenHash.sha256Hex(refreshToken.getToken().getTokenValue()));
    }

    /** 重放探测与烧族：族谱命中历史哈希即删该 user+client 全部授权行、族谱标 BURNED（重复重放幂等）。 */
    private void burnFamilyIfReplayed(String token) {
        tokenFamilyService
                .findByRefreshTokenHash(TokenHash.sha256Hex(token))
                .ifPresent(row -> burnFamily(row.principalName(), row.registeredClientId()));
    }

    private void burnFamily(String principalName, String registeredClientId) {
        Set<String> ids = authorizationIdsByFamily.get(principalName + " " + registeredClientId);
        if (ids != null) {
            for (String id : Set.copyOf(ids)) {
                OAuth2Authorization authorization = delegate.findById(id);
                if (authorization != null) {
                    delegate.remove(authorization);
                    ids.remove(id);
                }
            }
        }
        tokenFamilyService.burnFamily(principalName, registeredClientId);
    }

    private static String familyKey(OAuth2Authorization authorization) {
        return authorization.getPrincipalName() + " " + authorization.getRegisteredClientId();
    }
}
