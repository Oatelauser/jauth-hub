package io.github.oatelauser.jauth.core.token;

import java.util.List;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenClaimsContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

/**
 * opaque access token 的 claims 定制器（消费面一）：把全部 {@link ClaimsContributor} 的贡献 并入 opaque access token
 * claims——内省端点（/introspect）读取的正是这份 claims（SPEC §3 内省富化）。
 *
 * <p>B4 装配为 {@code OAuth2TokenCustomizer<OAuth2TokenClaimsContext>} bean， 由框架
 * OAuth2AccessTokenGenerator 在签发 opaque 令牌时调用。
 *
 * @author oatelauser
 */
public class OpaqueAccessTokenCustomizer implements OAuth2TokenCustomizer<OAuth2TokenClaimsContext> {

    private final ContributedClaims contributedClaims;

    public OpaqueAccessTokenCustomizer(List<ClaimsContributor> contributors) {
        this.contributedClaims = new ContributedClaims(contributors);
    }

    @Override
    public void customize(OAuth2TokenClaimsContext context) {
        contributedClaims.from(context).forEach(context.getClaims()::claim);
    }
}
