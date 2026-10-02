package io.github.oatelauser.jauth.core.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oatelauser.jauth.core.client.JauthJdbcRegisteredClientRepository;
import io.github.oatelauser.jauth.core.support.IntegrationTestSupport;
import io.github.oatelauser.jauth.core.token.JdbcTokenFamilyService;
import io.github.oatelauser.jauth.core.util.UuidV7;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

/**
 * JDBC 版按主体全量清剿单测（v1.3 D1）：目标主体跨 client 的授权行全删（内省即失效）、族谱整主体
 * BURNED，他人行不动；先删授权后烧族的顺序中断自愈语义由烧族路径既有测试覆盖。
 *
 * @author oatelauser
 */
class JdbcPrincipalAuthorizationRevokerTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-10-02T10:00:00Z");

    /** 占位主体名（非真实账号）。 */
    private static final String ALICE = "alice";

    private static final String BOB = "bob";

    private JdbcTemplate jdbcTemplate;

    private JauthJdbcOAuth2AuthorizationService authorizationService;

    private JdbcPrincipalAuthorizationRevoker revoker;

    private RegisteredClient clientOne;

    private RegisteredClient clientTwo;

    @BeforeEach
    void setUp() {
        this.jdbcTemplate = IntegrationTestSupport.migratedJdbcTemplate("jauth-principal-revoker");
        JauthJdbcRegisteredClientRepository clientRepository =
                new JauthJdbcRegisteredClientRepository(this.jdbcTemplate);
        this.clientOne = testClient("revoker-client-1");
        this.clientTwo = testClient("revoker-client-2");
        clientRepository.save(this.clientOne);
        clientRepository.save(this.clientTwo);
        this.authorizationService = new JauthJdbcOAuth2AuthorizationService(
                this.jdbcTemplate, clientRepository, new JdbcTokenFamilyService(this.jdbcTemplate));
        this.revoker =
                new JdbcPrincipalAuthorizationRevoker(this.jdbcTemplate, new JdbcTokenFamilyService(this.jdbcTemplate));
    }

    @Test
    @DisplayName("按主体清剿：跨 client 授权行全删、族谱整主体 BURNED，他人不受扰")
    void revokeAllDeletesEveryClientsRowsAndBurnsWholePrincipal() {
        saveAuthorization("auth-alice-1", ALICE, this.clientOne, "alice-r1");
        saveAuthorization("auth-alice-2", ALICE, this.clientTwo, "alice-r2");
        saveAuthorization("auth-bob-1", BOB, this.clientOne, "bob-r1");

        int deleted = this.revoker.revokeAll(ALICE);

        assertThat(deleted).isEqualTo(2);
        assertThat(authorizationRows(ALICE)).isZero();
        assertThat(familyStatuses(ALICE)).containsExactly("BURNED", "BURNED");
        assertThat(this.authorizationService.findByToken(refresh("bob-r1"), OAuth2TokenType.REFRESH_TOKEN))
                .as("bob 的授权仍可用")
                .isNotNull();
        assertThat(familyStatuses(BOB)).containsExactly("ACTIVE");
        assertThat(this.revoker.revokeAll(ALICE)).as("幂等：二轮删零行").isZero();
    }

    private void saveAuthorization(String id, String principal, RegisteredClient client, String generation) {
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                access(generation),
                ISSUED_AT,
                ISSUED_AT.plusSeconds(7200),
                Set.of("openid"));
        OAuth2RefreshToken refreshToken =
                new OAuth2RefreshToken(refresh(generation), ISSUED_AT, ISSUED_AT.plusSeconds(2592000));
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .id(id)
                .principalName(principal)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of("openid"))
                .token(accessToken)
                .refreshToken(refreshToken)
                .build();
        this.authorizationService.save(authorization);
    }

    private static RegisteredClient testClient(String clientId) {
        return RegisteredClient.withId(UuidV7.generate().toString())
                .clientId(clientId)
                .clientName(clientId)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("https://placeholder.example.com/callback")
                .scope("openid")
                .build();
    }

    private Integer authorizationRows(String principalName) {
        return this.jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM oauth2_authorization WHERE principal_name = ?", Integer.class, principalName);
    }

    private List<String> familyStatuses(String principalName) {
        return this.jdbcTemplate.queryForList(
                "SELECT status FROM jauth_token_family WHERE principal_name = ?", String.class, principalName);
    }

    private static String refresh(String generation) {
        return "placeholder-refresh-" + generation + "-not-real";
    }

    private static String access(String generation) {
        return "placeholder-access-" + generation + "-not-real";
    }
}
