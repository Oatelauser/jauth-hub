package io.github.oatelauser.jauth.starter;

import io.github.oatelauser.jauth.core.authorization.PrincipalAuthorizationRevoker;
import io.github.oatelauser.jauth.core.token.InMemoryTokenFamilyService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.util.Assert;

/**
 * 授权全量清剿的内存实现（v1.3 D1，memory 存储模式装配）。JDBC 版按 SQL 直删；内存模式的授权对象
 * 只活在授权服务装饰链的最内层（不可按主体枚举），故经 {@link InMemoryTokenFamilyService} 的授权 id
 * 索引（由 {@link FamilyAwareInMemoryAuthorizationService} 在 save/remove 维护）枚举后逐条
 * findById/remove——remove 走的是装配出的完整装饰链，每条授权自然产生 token.revoked 审计。
 *
 * <p>索引允许陈旧项（RTR 熔断路径直接 delegate.remove 不回写索引）：findById 落空即跳过。
 *
 * @author oatelauser
 */
final class InMemoryPrincipalAuthorizationRevoker implements PrincipalAuthorizationRevoker {

    private final OAuth2AuthorizationService authorizationService;

    private final InMemoryTokenFamilyService tokenFamilyService;

    InMemoryPrincipalAuthorizationRevoker(
            OAuth2AuthorizationService authorizationService, InMemoryTokenFamilyService tokenFamilyService) {
        Assert.notNull(authorizationService, "authorizationService cannot be null");
        Assert.notNull(tokenFamilyService, "tokenFamilyService cannot be null");
        this.authorizationService = authorizationService;
        this.tokenFamilyService = tokenFamilyService;
    }

    @Override
    public int revokeAll(String principalName) {
        Assert.hasText(principalName, "principalName cannot be empty");
        int removed = 0;
        for (String authorizationId : this.tokenFamilyService.authorizationIdsByPrincipal(principalName)) {
            OAuth2Authorization authorization = this.authorizationService.findById(authorizationId);
            if (authorization != null) {
                this.authorizationService.remove(authorization);
                removed++;
            }
        }
        this.tokenFamilyService.burnAllByPrincipal(principalName);
        return removed;
    }
}
