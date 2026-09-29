package io.github.oatelauser.jauth.core.token;

import io.github.oatelauser.jauth.core.user.JauthUser;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * 默认 claims 贡献者：贡献 sub（jauth_user.id，全网稳定的用户标识）与 username（登录名）。
 *
 * <p>用户经可注入的查找函数取得（B4 以 {@code UserRepository::findByUsername} 接线，principal name 即登录名）；查无此用户（如
 * client_credentials 的客户端主体）返回空 Map，不贡献。
 *
 * <p>orgs（含角色）不在此实现——v1.1 平台层接线（见 {@link ClaimsContributor} 接口注释）。
 *
 * @author oatelauser
 */
public class DefaultClaimsContributor implements ClaimsContributor {

    /** claims 键名：sub（OIDC 标准，此处覆写为 jauth 用户 id，与内省/sub口径一致）。 */
    static final String CLAIM_SUB = "sub";

    static final String CLAIM_USERNAME = "username";

    private final Function<String, @Nullable JauthUser> userLookup;

    public DefaultClaimsContributor(Function<String, @Nullable JauthUser> userLookup) {
        this.userLookup = userLookup;
    }

    @Override
    public Map<String, Object> contribute(TokenClaimsContext context) {
        JauthUser user = userLookup.apply(context.principalName());
        if (user == null) {
            return Map.of();
        }
        Map<String, Object> claims = new HashMap<>(4);
        claims.put(CLAIM_SUB, user.id());
        claims.put(CLAIM_USERNAME, user.username());
        return claims;
    }
}
