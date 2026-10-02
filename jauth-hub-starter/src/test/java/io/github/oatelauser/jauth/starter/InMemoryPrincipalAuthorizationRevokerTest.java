package io.github.oatelauser.jauth.starter;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oatelauser.jauth.core.token.InMemoryTokenFamilyService;
import io.github.oatelauser.jauth.core.token.TokenHash;
import io.github.oatelauser.jauth.core.util.UuidV7;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

/**
 * 内存模式按主体全量清剿单测（v1.3 D1）：链式装配同 starter memory 分支（FamilyAware 包框架内存实现），
 * 断言目标主体跨 client 的授权全部移除（findByToken 落空）、族谱整主体 BURNED、他人不受扰。
 *
 * @author oatelauser
 */
class InMemoryPrincipalAuthorizationRevokerTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-10-02T10:00:00Z");

    /** 占位令牌值（非真实凭据）。 */
    private static final String ALICE = "alice";

    private static final String BOB = "bob";

    private InMemoryTokenFamilyService tokenFamilyService;

    private FamilyAwareInMemoryAuthorizationService authorizationService;

    private InMemoryPrincipalAuthorizationRevoker revoker;

    @BeforeEach
    void setUp() {
        this.tokenFamilyService = new InMemoryTokenFamilyService();
        this.authorizationService = new FamilyAwareInMemoryAuthorizationService(
                new InMemoryOAuth2AuthorizationService(), tokenFamilyService);
        this.revoker = new InMemoryPrincipalAuthorizationRevoker(authorizationService, tokenFamilyService);
    }

    @Test
    @DisplayName("按主体清剿：跨 client 全移除+全 BURNED；他人授权与族不受扰；再清剿幂等归零")
    void revokeAllRemovesEveryClientsAuthorizationAndBurnsWholePrincipal() {
        saveAuthorization("auth-alice-1", ALICE, "client-1", "alice-r1");
        saveAuthorization("auth-alice-2", ALICE, "client-2", "alice-r2");
        saveAuthorization("auth-bob-1", BOB, "client-1", "bob-r1");

        int removed = this.revoker.revokeAll(ALICE);

        assertThat(removed).isEqualTo(2);
        assertThat(this.authorizationService.findByToken(refresh("alice-r1"), OAuth2TokenType.REFRESH_TOKEN))
                .as("alice 全部授权已移除")
                .isNull();
        assertThat(this.authorizationService.findByToken(refresh("alice-r2"), OAuth2TokenType.REFRESH_TOKEN))
                .isNull();
        assertThat(this.authorizationService.findByToken(refresh("bob-r1"), OAuth2TokenType.REFRESH_TOKEN))
                .as("bob 不受牵连")
                .isNotNull();
        assertThat(this.tokenFamilyService
                        .findByRefreshTokenHash(TokenHash.sha256Hex(refresh("alice-r1")))
                        .orElseThrow()
                        .status())
                .isEqualTo(InMemoryTokenFamilyService.STATUS_BURNED);
        assertThat(this.tokenFamilyService
                        .findByRefreshTokenHash(TokenHash.sha256Hex(refresh("bob-r1")))
                        .orElseThrow()
                        .status())
                .isEqualTo(InMemoryTokenFamilyService.STATUS_ACTIVE);

        assertThat(this.revoker.revokeAll(ALICE)).as("幂等：二轮无残留").isZero();
    }

    private void saveAuthorization(String id, String principal, String clientId, String generation) {
        RegisteredClient client = RegisteredClient.withId(UuidV7.generate().toString())
                .clientId(clientId)
                .clientName(clientId)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("https://placeholder.example.com/callback")
                .scope("openid")
                .build();
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

    private static String refresh(String generation) {
        return "placeholder-refresh-" + generation + "-not-real";
    }

    private static String access(String generation) {
        return "placeholder-access-" + generation + "-not-real";
    }
}
