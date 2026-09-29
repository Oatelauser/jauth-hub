package io.github.oatelauser.jauth.core.token;

import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * claims 贡献上下文：正在签发/富化的令牌的三要素。
 *
 * <p>两侧防御拷贝（入参 {@code Set.copyOf}、访问器每次新拷）保证贡献者拿到的 scope 集不可被回写—— 上下文在多个贡献者间共享，任一贡献者的可变视图都会污染其余贡献者。
 *
 * @param principalName 主体名（框架 principal name，v1 即登录名）
 * @param registeredClientId 客户端 id（v1.1 orgs 贡献者按 client x org 查安装关系）
 * @param authorizedScopes 本次授权的 scope 集（构造即冻结为不可变副本）
 * @author oatelauser
 */
public record TokenClaimsContext(
        String principalName, @Nullable String registeredClientId, Set<String> authorizedScopes) {

    public TokenClaimsContext {
        authorizedScopes = Set.copyOf(authorizedScopes);
    }

    @Override
    public Set<String> authorizedScopes() {
        return Set.copyOf(authorizedScopes);
    }
}
