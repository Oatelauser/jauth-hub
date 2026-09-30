package io.github.oatelauser.jauth.core.client;

import io.github.oatelauser.jauth.core.support.IntegrationTestSupport;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

/**
 * {@link JdbcClientOwnerResolver} 契约测试：H2(PostgreSQL 模式) + Flyway 真库，seed 走
 * {@link JauthJdbcRegisteredClientRepository#save(RegisteredClient, ClientOwner)} 双列写入的既有路径。
 *
 * @author oatelauser
 */
class JdbcClientOwnerResolverTest extends AbstractClientOwnerResolverContractTest {

    private final JauthJdbcRegisteredClientRepository repository = new JauthJdbcRegisteredClientRepository(
            IntegrationTestSupport.migratedJdbcTemplate("jauth-owner-resolver"));

    @Override
    protected ClientOwnerResolver resolver() {
        return new JdbcClientOwnerResolver(this.repository);
    }

    @Override
    protected void seed(String registeredClientId, ClientOwner owner) {
        this.repository.save(registeredClient(registeredClientId), owner);
    }

    private static RegisteredClient registeredClient(String id) {
        return RegisteredClient.withId(id)
                .clientId("client-" + id)
                .clientName("owner-resolver-test-client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .build();
    }
}
