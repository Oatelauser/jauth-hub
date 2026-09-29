package io.github.oatelauser.jauth.core.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oatelauser.jauth.core.client.JauthJdbcRegisteredClientRepository;
import io.github.oatelauser.jauth.core.support.IntegrationTestSupport;
import io.github.oatelauser.jauth.core.token.JdbcTokenFamilyService;
import io.github.oatelauser.jauth.core.token.TokenHash;
import io.github.oatelauser.jauth.core.util.UuidV7;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

/**
 * 令牌哈希手术往返单测：findByToken(明文) 命中 + 直查 DB 断言列值是哈希非明文 + state 原文存取 + 再 save（吊销路径语义）不双重哈希。
 *
 * @author oatelauser
 */
class JauthJdbcOAuth2AuthorizationServiceTest {

    /** 占位令牌值：非真实凭据。Base64URL 形状，与 64 位十六进制的哈希形状可区分。 */
    private static final String RAW_ACCESS_TOKEN = "placeholder-access-token-value-not-real";

    private static final String RAW_REFRESH_TOKEN = "placeholder-refresh-token-value-not-real";

    private static final String STATE_VALUE = "placeholder-state-not-real";

    private JdbcTemplate jdbcTemplate;

    private JauthJdbcOAuth2AuthorizationService authorizationService;

    private RegisteredClient registeredClient;

    @BeforeEach
    void setUp() {
        this.jdbcTemplate = IntegrationTestSupport.migratedJdbcTemplate("jauth-authorization-hash");
        JauthJdbcRegisteredClientRepository clientRepository =
                new JauthJdbcRegisteredClientRepository(this.jdbcTemplate);
        this.registeredClient = testClient();
        clientRepository.save(this.registeredClient);
        this.authorizationService = new JauthJdbcOAuth2AuthorizationService(
                this.jdbcTemplate, clientRepository, new JdbcTokenFamilyService(this.jdbcTemplate));
    }

    @Test
    void storesHashAndFindsByRawToken() {
        OAuth2Authorization authorization = authorizationWithTokens();
        this.authorizationService.save(authorization);

        assertThat(this.authorizationService.findByToken(RAW_ACCESS_TOKEN, OAuth2TokenType.ACCESS_TOKEN))
                .isNotNull()
                .extracting(OAuth2Authorization::getId)
                .isEqualTo(authorization.getId());
        assertThat(this.authorizationService.findByToken(RAW_REFRESH_TOKEN, OAuth2TokenType.REFRESH_TOKEN))
                .isNotNull()
                .extracting(OAuth2Authorization::getId)
                .isEqualTo(authorization.getId());
    }

    @Test
    void findByTokenBackfillsPlaintextForFrameworkValueComparison() {
        OAuth2Authorization authorization = authorizationWithTokens();
        this.authorizationService.save(authorization);

        // 框架内省/撤销 provider 的二次值比对：authorization.getToken(明文) 必须命中（B6 缺陷 1）
        OAuth2Authorization found =
                this.authorizationService.findByToken(RAW_ACCESS_TOKEN, OAuth2TokenType.ACCESS_TOKEN);
        assertThat(found.getToken(RAW_ACCESS_TOKEN)).as("明文回填后值比对命中").isNotNull();
        assertThat(found.getAccessToken().getToken().getTokenValue()).isEqualTo(RAW_ACCESS_TOKEN);
        assertThat(found.getToken(RAW_ACCESS_TOKEN).getClaims())
                .as("builder 重建保留 token 元数据 claims（内省富化数据源）")
                .containsEntry("sub", "0192-placeholder-user-id")
                .containsEntry("username", "alice");

        // 未知类型入口（内省/吊销实际传入 null 类型）同样回填
        OAuth2Authorization unknownTypeFound = this.authorizationService.findByToken(RAW_REFRESH_TOKEN, null);
        assertThat(unknownTypeFound.getToken(RAW_REFRESH_TOKEN))
                .as("null 类型探测路径同样回填明文")
                .isNotNull();

        // 回填明文再 save（撤销路径语义）：库列仍回到哈希，不落明文
        this.authorizationService.save(found);
        String storedAccessToken = this.jdbcTemplate.queryForObject(
                "SELECT access_token_value FROM oauth2_authorization WHERE id = ?",
                String.class,
                authorization.getId());
        assertThat(storedAccessToken).isEqualTo(TokenHash.sha256Hex(RAW_ACCESS_TOKEN));
    }

    @Test
    void databaseColumnHoldsHashNotPlaintext() {
        OAuth2Authorization authorization = authorizationWithTokens();
        this.authorizationService.save(authorization);

        String storedAccessToken = this.jdbcTemplate.queryForObject(
                "SELECT access_token_value FROM oauth2_authorization WHERE id = ?",
                String.class,
                authorization.getId());
        String storedRefreshToken = this.jdbcTemplate.queryForObject(
                "SELECT refresh_token_value FROM oauth2_authorization WHERE id = ?",
                String.class,
                authorization.getId());

        assertThat(storedAccessToken).isEqualTo(TokenHash.sha256Hex(RAW_ACCESS_TOKEN));
        assertThat(storedRefreshToken).isEqualTo(TokenHash.sha256Hex(RAW_REFRESH_TOKEN));
    }

    @Test
    void stateIsStoredAndFoundAsPlaintext() {
        OAuth2Authorization authorization = authorizationWithTokens();
        this.authorizationService.save(authorization);

        String storedState = this.jdbcTemplate.queryForObject(
                "SELECT state FROM oauth2_authorization WHERE id = ?", String.class, authorization.getId());
        assertThat(storedState).isEqualTo(STATE_VALUE);

        OAuth2Authorization found =
                this.authorizationService.findByToken(STATE_VALUE, new OAuth2TokenType(OAuth2ParameterNames.STATE));
        assertThat(found).isNotNull();
    }

    @Test
    void unknownTokenTypeLookupStillHitsHashedTokens() {
        OAuth2Authorization authorization = authorizationWithTokens();
        this.authorizationService.save(authorization);

        OAuth2Authorization found = this.authorizationService.findByToken(RAW_ACCESS_TOKEN, null);
        assertThat(found).isNotNull().extracting(OAuth2Authorization::getId).isEqualTo(authorization.getId());
    }

    @Test
    void resavingReadBackAuthorizationDoesNotDoubleHash() {
        OAuth2Authorization authorization = authorizationWithTokens();
        this.authorizationService.save(authorization);

        // 吊销/轮转路径：读回（值已是哈希）再整体 save，哈希值必须原样保持
        OAuth2Authorization readBack =
                this.authorizationService.findByToken(RAW_ACCESS_TOKEN, OAuth2TokenType.ACCESS_TOKEN);
        this.authorizationService.save(readBack);

        String storedAccessToken = this.jdbcTemplate.queryForObject(
                "SELECT access_token_value FROM oauth2_authorization WHERE id = ?",
                String.class,
                authorization.getId());
        assertThat(storedAccessToken).isEqualTo(TokenHash.sha256Hex(RAW_ACCESS_TOKEN));
        assertThat(this.authorizationService.findByToken(RAW_ACCESS_TOKEN, OAuth2TokenType.ACCESS_TOKEN))
                .isNotNull();
    }

    @Test
    void removeDeletesByPrimaryKey() {
        OAuth2Authorization authorization = authorizationWithTokens();
        this.authorizationService.save(authorization);

        OAuth2Authorization stored =
                this.authorizationService.findByToken(RAW_ACCESS_TOKEN, OAuth2TokenType.ACCESS_TOKEN);
        this.authorizationService.remove(stored);

        assertThat(this.authorizationService.findByToken(RAW_ACCESS_TOKEN, OAuth2TokenType.ACCESS_TOKEN))
                .isNull();
    }

    private RegisteredClient testClient() {
        return RegisteredClient.withId(UuidV7.generate().toString())
                .clientId("hash-test-client")
                .clientName("hash-test-client")
                .clientAuthenticationMethod(
                        org.springframework.security.oauth2.core.ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("https://placeholder.example.com/callback")
                .scope("openid")
                .clientSettings(ClientSettings.builder().requireProofKey(true).build())
                .tokenSettings(TokenSettings.builder().build())
                .build();
    }

    private OAuth2Authorization authorizationWithTokens() {
        Instant issuedAt = Instant.parse("2026-09-29T10:00:00Z");
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                RAW_ACCESS_TOKEN,
                issuedAt,
                issuedAt.plusSeconds(7200),
                Set.of("openid"));
        OAuth2RefreshToken refreshToken =
                new OAuth2RefreshToken(RAW_REFRESH_TOKEN, issuedAt, issuedAt.plusSeconds(2592000));
        return OAuth2Authorization.withRegisteredClient(this.registeredClient)
                .id(UuidV7.generate().toString())
                .principalName("alice")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of("openid"))
                .attribute(OAuth2ParameterNames.STATE, STATE_VALUE)
                .token(
                        accessToken,
                        metadata -> metadata.put(
                                OAuth2Authorization.Token.CLAIMS_METADATA_NAME,
                                new java.util.HashMap<>(
                                        java.util.Map.of("sub", "0192-placeholder-user-id", "username", "alice"))))
                .refreshToken(refreshToken)
                .build();
    }
}
