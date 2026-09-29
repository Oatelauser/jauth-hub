package io.github.oatelauser.jauth.core.token;

import java.util.List;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

/**
 * OIDC id_token 的 claims 定制器（消费面二）：把全部 {@link ClaimsContributor} 的贡献并入 id_token 编码 claims。userinfo
 * 端点复用授权里 id_token 的 claims，故一并富化（SPEC §3）。
 *
 * <p>只认领 id_token 编码（access token 为 opaque，JWT access token 的富化留 v2 按需开）。 B4 装配为 {@code
 * OAuth2TokenCustomizer<JwtEncodingContext>} bean。
 *
 * @author oatelauser
 */
public class OidcIdTokenCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    private final ContributedClaims contributedClaims;

    public OidcIdTokenCustomizer(List<ClaimsContributor> contributors) {
        this.contributedClaims = new ContributedClaims(contributors);
    }

    @Override
    public void customize(JwtEncodingContext context) {
        if (!isIdTokenEncoding(context)) {
            return;
        }
        contributedClaims.from(context).forEach(context.getClaims()::claim);
    }

    private static boolean isIdTokenEncoding(JwtEncodingContext context) {
        return context.getTokenType() != null
                && OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue());
    }
}
