package io.github.oatelauser.jauth.core.token;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;

/**
 * 贡献者列表 → claims 合并的共用逻辑（opaque 与 OIDC 两个定制器共用，包内可见）。
 *
 * @author oatelauser
 */
final class ContributedClaims {

    private final List<ClaimsContributor> contributors;

    ContributedClaims(List<ClaimsContributor> contributors) {
        this.contributors = List.copyOf(contributors);
    }

    /**
     * 从框架令牌上下文提取三要素并合并全部贡献者的键值。
     *
     * @param context 框架令牌上下文（opaque claims / JWT 编码两形态）
     * @return 合并后的 claims（后注册者覆盖同键）；无贡献者为空 Map
     */
    Map<String, Object> from(OAuth2TokenContext context) {
        TokenClaimsContext claimsContext =
                new TokenClaimsContext(principalName(context), clientId(context), context.getAuthorizedScopes());
        Map<String, Object> merged = new LinkedHashMap<>();
        for (ClaimsContributor contributor : contributors) {
            Map<String, Object> contributed = contributor.contribute(claimsContext);
            if (contributed != null) {
                merged.putAll(contributed);
            }
        }
        return merged;
    }

    /** 主体名：优先取授权行（刷新/内省路径上下文里授权已存在），退回认证主体。 */
    private static String principalName(OAuth2TokenContext context) {
        OAuth2Authorization authorization = context.getAuthorization();
        if (authorization != null) {
            return authorization.getPrincipalName();
        }
        Authentication principal = context.getPrincipal();
        return principal.getName();
    }

    private static String clientId(OAuth2TokenContext context) {
        RegisteredClient registeredClient = context.getRegisteredClient();
        return registeredClient == null ? null : registeredClient.getClientId();
    }
}
