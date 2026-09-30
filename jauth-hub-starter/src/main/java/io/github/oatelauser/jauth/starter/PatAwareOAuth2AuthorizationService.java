package io.github.oatelauser.jauth.starter;

import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.util.Assert;

/**
 * PAT 叠加授权服务（B5 遗留闭环的装配件）：授权令牌未命中时按 PAT 回退——持有 PAT 的调用方在
 * /introspect 与 /me 获得与授权令牌同构的 active/身份语义，PAT 持有者过得了资源服务器。
 *
 * <p><b>落点论证（任务"授权服务层 vs 专用叠加层"二选一）</b>：选叠加层（装饰 OAuth2AuthorizationService
 * 接口，jdbc 模式装配），理由：框架内省 provider 对 findByToken 的返回值有完整消费链（claims 组装、
 * Token.isActive、client_id 反查），在授权服务层合成框架原生形状的 OAuth2Authorization 即可零改框架
 * provider；改内省 provider 本身（SAS 无公开扩展点）需复制其认证器装配，得不偿失。PAT 为 jdbc 专属
 * （memory 模式禁用），叠加层只在 jdbc 装配分支出现，不污染 memory 链。
 *
 * <p>回退面：tokenType 为 null（内省/吊销入口）或 ACCESS_TOKEN（/me 解析）。命中即回填 last_used_at
 * （{@link PatIntrospectionSupport} 类注释的取舍：只记使用，不打审计）。
 *
 * @author oatelauser
 */
final class PatAwareOAuth2AuthorizationService implements OAuth2AuthorizationService {

    private final OAuth2AuthorizationService delegate;

    private final PatIntrospectionSupport patSupport;

    PatAwareOAuth2AuthorizationService(OAuth2AuthorizationService delegate, PatIntrospectionSupport patSupport) {
        Assert.notNull(delegate, "delegate cannot be null");
        Assert.notNull(patSupport, "patSupport cannot be null");
        this.delegate = delegate;
        this.patSupport = patSupport;
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        delegate.save(authorization);
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        delegate.remove(authorization);
    }

    @Override
    public @Nullable OAuth2Authorization findById(String id) {
        return delegate.findById(id);
    }

    @Override
    public @Nullable OAuth2Authorization findByToken(String token, @Nullable OAuth2TokenType tokenType) {
        OAuth2Authorization found = delegate.findByToken(token, tokenType);
        if (found != null) {
            return found;
        }
        if (tokenType != null && !OAuth2TokenType.ACCESS_TOKEN.equals(tokenType)) {
            return null;
        }
        // PAT 回退：命中即合成授权对象并回填最近使用
        return patSupport
                .findByTokenValue(token)
                .map(hit -> {
                    patSupport.touchLastUsed(hit.patId());
                    return patSupport.toAuthorization(token, hit);
                })
                .orElse(null);
    }
}
