package io.github.oatelauser.jauth.core.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oatelauser.jauth.core.support.IntegrationTestSupport;
import io.github.oatelauser.jauth.core.util.UuidV7;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

/**
 * {@link JauthJdbcRegisteredClientRepository} 单测：框架 13 列读写复用 + owner 两列读写往返。
 *
 * @author oatelauser
 */
class JauthJdbcRegisteredClientRepositoryTest {

    private static final String USER_ID = "018f0000-0000-7000-8000-0000000000aa";

    private static final String ORG_ID = "018f0000-0000-7000-8000-0000000000bb";

    private JdbcTemplate jdbcTemplate;

    private JauthJdbcRegisteredClientRepository repository;

    @BeforeEach
    void setUp() {
        this.jdbcTemplate = IntegrationTestSupport.migratedJdbcTemplate("jauth-client-owner");
        this.repository = new JauthJdbcRegisteredClientRepository(this.jdbcTemplate);
    }

    @Test
    void savesAndFindsClientWithUserOwner() {
        RegisteredClient client = testClient("owner-user-client");
        this.repository.save(client, ClientOwner.ofUser(USER_ID));

        assertThat(this.repository.findByClientId("owner-user-client")).isNotNull();
        assertThat(this.repository.findOwnerById(client.getId())).isEqualTo(ClientOwner.ofUser(USER_ID));
    }

    @Test
    void savesClientWithOrgOwnerAndPlatformBuiltin() {
        RegisteredClient orgClient = testClient("owner-org-client");
        this.repository.save(orgClient, ClientOwner.ofOrg(ORG_ID));
        RegisteredClient builtinClient = testClient("builtin-client");
        this.repository.save(builtinClient, ClientOwner.platform());

        assertThat(this.repository.findOwnerById(orgClient.getId())).isEqualTo(ClientOwner.ofOrg(ORG_ID));
        assertThat(this.repository.findOwnerById(builtinClient.getId())).isEqualTo(ClientOwner.platform());
    }

    @Test
    void ownerUpdateOverwritesPreviousOwner() {
        RegisteredClient client = testClient("owner-flip-client");
        this.repository.save(client, ClientOwner.ofUser(USER_ID));
        this.repository.save(client, ClientOwner.ofOrg(ORG_ID));

        assertThat(this.repository.findOwnerById(client.getId())).isEqualTo(ClientOwner.ofOrg(ORG_ID));
    }

    @Test
    void frameworkSaveWithoutOwnerLeavesColumnsNull() {
        RegisteredClient client = testClient("framework-plain-client");
        this.repository.save(client);

        assertThat(this.repository.findByClientId("framework-plain-client")).isNotNull();
        assertThat(this.repository.findOwnerById(client.getId())).isEqualTo(ClientOwner.platform());
    }

    @Test
    void findOwnerOfUnknownClientReturnsNull() {
        assertThat(this.repository.findOwnerById("no-such-client")).isNull();
    }

    private RegisteredClient testClient(String clientId) {
        return RegisteredClient.withId(UuidV7.generate().toString())
                .clientId(clientId)
                .clientName(clientId)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://placeholder.example.com/callback")
                .scope("openid")
                .clientSettings(ClientSettings.builder().requireProofKey(true).build())
                .tokenSettings(TokenSettings.builder().build())
                .build();
    }
}
