package io.github.oatelauser.jauth.core.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oatelauser.jauth.core.client.JauthJdbcRegisteredClientRepository;
import io.github.oatelauser.jauth.core.support.IntegrationTestSupport;
import io.github.oatelauser.jauth.core.token.JdbcTokenFamilyService;
import io.github.oatelauser.jauth.core.token.TokenHash;
import io.github.oatelauser.jauth.core.util.UuidV7;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

/**
 * RTR 族谱熔断端到端单测（SPEC §6：refresh 用后即轮转，重放→整族熔断）： 正常轮转链 r1→r2→r3 可用；重放最老 r1 / 次新 r2 都触发烧族；烧族后该
 * user+client 的 access token 内省与全部授权即时失效（须重新登录授权）。
 *
 * <p>轮转模拟 = 同一授权行以新令牌值重 save（框架刷新路径即如此：同一 id，值更新）。
 *
 * @author oatelauser
 */
class RtrTokenFamilyTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-09-29T10:00:00Z");

    /** 占位令牌值：非真实凭据，Base64URL 形状与哈希形状可区分。 */
    private static final String PRINCIPAL = "alice";

    private JdbcTemplate jdbcTemplate;

    private JauthJdbcOAuth2AuthorizationService authorizationService;

    private RegisteredClient registeredClient;

    @BeforeEach
    void setUp() {
        this.jdbcTemplate = IntegrationTestSupport.migratedJdbcTemplate("jauth-rtr-family");
        JauthJdbcRegisteredClientRepository clientRepository =
                new JauthJdbcRegisteredClientRepository(this.jdbcTemplate);
        this.registeredClient = testClient();
        clientRepository.save(this.registeredClient);
        this.authorizationService = new JauthJdbcOAuth2AuthorizationService(
                this.jdbcTemplate, clientRepository, new JdbcTokenFamilyService(this.jdbcTemplate));
    }

    @Test
    void rotationChainThroughThreeGenerationsStaysUsable() {
        rotateChain("r1", "r2", "r3");

        assertThat(authorizationService.findByToken(refresh("r3"), OAuth2TokenType.REFRESH_TOKEN))
                .isNotNull();
        assertThat(authorizationService.findByToken(access("r3"), OAuth2TokenType.ACCESS_TOKEN))
                .isNotNull();

        List<String> statuses = familyStatuses();
        assertThat(statuses).containsExactlyInAnyOrder("ACTIVE", "SUPERSEDED", "SUPERSEDED");
        assertThat(activeRowHash()).isEqualTo(TokenHash.sha256Hex(refresh("r3")));
        assertThat(familyGenerations()).containsExactlyInAnyOrder(1, 2, 3);
    }

    @Test
    void replayOfOldestRefreshTokenBurnsWholeFamily() {
        rotateChain("r1", "r2", "r3");

        assertThat(authorizationService.findByToken(refresh("r1"), OAuth2TokenType.REFRESH_TOKEN))
                .isNull();

        assertThat(authorizationService.findByToken(access("r3"), OAuth2TokenType.ACCESS_TOKEN))
                .as("烧族后该 user+client 的 access token 内省即失效")
                .isNull();
        assertThat(familyStatuses()).containsExactly("BURNED", "BURNED", "BURNED");
        assertThatRemainingAuthorizationRows(0);
    }

    @Test
    void replayOfMiddleRefreshTokenBurnsWholeFamily() {
        rotateChain("r1", "r2", "r3");

        assertThat(authorizationService.findByToken(refresh("r2"), OAuth2TokenType.REFRESH_TOKEN))
                .isNull();

        assertThat(familyStatuses()).containsExactly("BURNED", "BURNED", "BURNED");
        assertThat(authorizationService.findByToken(refresh("r3"), OAuth2TokenType.REFRESH_TOKEN))
                .isNull();
    }

    @Test
    void introspectionLookupAlsoBurnsOnReplay() {
        rotateChain("r1", "r2", "r3");

        // tokenType=null 是内省/吊销入口的探测形态，同样要触发熔断
        assertThat(authorizationService.findByToken(refresh("r1"), null)).isNull();

        assertThat(familyStatuses()).containsExactly("BURNED", "BURNED", "BURNED");
        assertThatRemainingAuthorizationRows(0);
    }

    @Test
    void unknownRefreshTokenDoesNotTouchFamily() {
        rotateChain("r1");

        assertThat(authorizationService.findByToken("placeholder-never-issued-not-real", OAuth2TokenType.REFRESH_TOKEN))
                .isNull();

        assertThat(familyStatuses()).containsExactly("ACTIVE");
        assertThat(authorizationService.findByToken(access("r1"), OAuth2TokenType.ACCESS_TOKEN))
                .isNotNull();
    }

    @Test
    void burnedFamilyRequiresFreshLoginAndAcceptsNewFamilyRow() {
        rotateChain("r1", "r2");
        burnByReplaying("r1");

        // 重新登录授权：新授权行 + 新族谱 ACTIVE 行，旧 BURNED 行留痕
        saveAuthorization("r4");

        assertThat(authorizationService.findByToken(refresh("r4"), OAuth2TokenType.REFRESH_TOKEN))
                .isNotNull();
        assertThat(familyStatuses()).containsExactlyInAnyOrder("BURNED", "BURNED", "ACTIVE");
        assertThat(activeRowHash()).isEqualTo(TokenHash.sha256Hex(refresh("r4")));
    }

    private void rotateChain(String... generations) {
        for (String generation : generations) {
            saveAuthorization(generation);
        }
    }

    private void burnByReplaying(String refreshValue) {
        assertThat(authorizationService.findByToken(refresh(refreshValue), OAuth2TokenType.REFRESH_TOKEN))
                .isNull();
    }

    /** 框架刷新路径的等价动作：同一授权 id 以新令牌值整体重 save（行被 UPDATE 而非新插）。 */
    private void saveAuthorization(String generation) {
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                access(generation),
                ISSUED_AT,
                ISSUED_AT.plusSeconds(7200),
                Set.of("openid"));
        OAuth2RefreshToken refreshToken =
                new OAuth2RefreshToken(refresh(generation), ISSUED_AT, ISSUED_AT.plusSeconds(2592000));
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(this.registeredClient)
                .id("auth-rtr-1")
                .principalName(PRINCIPAL)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of("openid"))
                .token(accessToken)
                .refreshToken(refreshToken)
                .build();
        this.authorizationService.save(authorization);
    }

    private RegisteredClient testClient() {
        return RegisteredClient.withId(UuidV7.generate().toString())
                .clientId("rtr-test-client")
                .clientName("rtr-test-client")
                .clientAuthenticationMethod(
                        org.springframework.security.oauth2.core.ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("https://placeholder.example.com/callback")
                .scope("openid")
                .build();
    }

    private static String refresh(String generation) {
        return "placeholder-refresh-" + generation + "-not-real";
    }

    private static String access(String generation) {
        return "placeholder-access-" + generation + "-not-real";
    }

    private List<String> familyStatuses() {
        return this.jdbcTemplate.queryForList(
                "SELECT status FROM jauth_token_family WHERE principal_name = ? ORDER BY" + " generation",
                String.class,
                PRINCIPAL);
    }

    private List<Integer> familyGenerations() {
        return this.jdbcTemplate.queryForList(
                "SELECT generation FROM jauth_token_family WHERE principal_name = ?", Integer.class, PRINCIPAL);
    }

    private String activeRowHash() {
        return this.jdbcTemplate.queryForObject(
                "SELECT refresh_token_hash FROM jauth_token_family WHERE principal_name = ? AND" + " status = 'ACTIVE'",
                String.class,
                PRINCIPAL);
    }

    private void assertThatRemainingAuthorizationRows(int expected) {
        Integer count = this.jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM oauth2_authorization WHERE principal_name = ?", Integer.class, PRINCIPAL);
        assertThat(count).isEqualTo(expected);
    }
}
