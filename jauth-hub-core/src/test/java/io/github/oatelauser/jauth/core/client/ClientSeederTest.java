package io.github.oatelauser.jauth.core.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oatelauser.jauth.core.support.IntegrationTestSupport;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

/**
 * {@link ClientSeeder} 单测：幂等（跑两遍不重复不报错）+ 内存/JDBC 两仓库通用 + secret 落库已编码。 secret 均为占位值。
 *
 * @author oatelauser
 */
class ClientSeederTest {

    /** 占位 secret：非真实凭据。 */
    private static final String PLACEHOLDER_SECRET = "placeholder-seed-secret-not-real";

    private ClientSeedProperties properties;

    private ClientSeeder seeder;

    @BeforeEach
    void setUp() {
        this.properties = new ClientSeedProperties();
        ClientSeedProperties.ClientSeed seed = new ClientSeedProperties.ClientSeed();
        seed.setClientId("seeded-demo-client");
        seed.setClientSecret(PLACEHOLDER_SECRET);
        seed.setClientName("seeded-demo-client");
        seed.setGrantTypes(List.of("authorization_code", "refresh_token"));
        seed.setRedirectUris(List.of("https://placeholder.example.com/callback"));
        seed.setScopes(List.of("openid", "profile"));
        this.properties.setClients(List.of(seed));
        this.seeder = new ClientSeeder(this.properties, PasswordEncoderFactories.createDelegatingPasswordEncoder());
    }

    @Test
    void seedingTwiceIntoMemoryRepositoryIsIdempotent() {
        // 框架内存仓库的构造器拒绝空注册表（varargs 空参也会命中 notEmpty 断言），先放一条占位注册
        RegisteredClientRepository repository = new InMemoryRegisteredClientRepository(bootstrapClient());

        this.seeder.seed(repository);
        this.seeder.seed(repository);

        RegisteredClient seeded = repository.findByClientId("seeded-demo-client");
        assertThat(seeded).isNotNull();
        assertThat(seeded.getClientSecret()).isNotEqualTo(PLACEHOLDER_SECRET).contains("{");
        assertThat(seeded.getClientSettings().isRequireProofKey())
                .as("SPEC 强制 PKCE")
                .isTrue();
        assertThat(seeded.getTokenSettings().getAccessTokenFormat())
                .as("SPEC §1 opaque 正典：种子客户端 access token 走 REFERENCE")
                .isEqualTo(OAuth2TokenFormat.REFERENCE);
    }

    @Test
    void seedingTwiceIntoJdbcRepositoryKeepsSingleRow() {
        JdbcTemplate jdbcTemplate = IntegrationTestSupport.migratedJdbcTemplate("jauth-client-seeder");
        JauthJdbcRegisteredClientRepository repository = new JauthJdbcRegisteredClientRepository(jdbcTemplate);

        this.seeder.seed(repository);
        this.seeder.seed(repository);

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM oauth2_registered_client WHERE client_id =" + " 'seeded-demo-client'",
                Integer.class);
        assertThat(rowCount).isEqualTo(1);
        assertThat(repository.findByClientId("seeded-demo-client")).isNotNull();
    }

    @Test
    void emptySeedListIsNoOp() {
        RegisteredClientRepository repository = new InMemoryRegisteredClientRepository(bootstrapClient());
        new ClientSeeder(new ClientSeedProperties(), PasswordEncoderFactories.createDelegatingPasswordEncoder())
                .seed(repository);
        assertThat(repository.findByClientId("seeded-demo-client")).isNull();
    }

    private RegisteredClient bootstrapClient() {
        return RegisteredClient.withId("memory-repository-bootstrap-placeholder")
                .clientId("bootstrap-placeholder")
                .clientName("bootstrap-placeholder")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://placeholder.example.com/callback")
                .scope("openid")
                .clientSettings(ClientSettings.builder().requireProofKey(true).build())
                .tokenSettings(TokenSettings.builder().build())
                .build();
    }
}
