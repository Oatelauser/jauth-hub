package io.github.oatelauser.jauth.core.client;

import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.util.Assert;

/**
 * {@link ClientOwnerResolver} 的 JDBC 实现：单查委托 {@link JauthJdbcRegisteredClientRepository#findOwnerById}
 * （owner 两列的既有读路径，不另持 SQL）。委托以函数捕获（同 AuditingOAuth2AuthorizationService 的 SpotBugs
 * EI_EXPOSE_REP2 取舍——仓储类带 save 可变方法,存成员即暴露内部表示）。
 *
 * @author oatelauser
 */
public class JdbcClientOwnerResolver implements ClientOwnerResolver {

    private final Function<String, @Nullable ClientOwner> ownerLookup;

    public JdbcClientOwnerResolver(JauthJdbcRegisteredClientRepository registeredClientRepository) {
        Assert.notNull(registeredClientRepository, "registeredClientRepository cannot be null");
        this.ownerLookup = registeredClientRepository::findOwnerById;
    }

    @Override
    public @Nullable ClientOwner findOwner(String registeredClientId) {
        Assert.hasText(registeredClientId, "registeredClientId cannot be empty");
        return this.ownerLookup.apply(registeredClientId);
    }
}
