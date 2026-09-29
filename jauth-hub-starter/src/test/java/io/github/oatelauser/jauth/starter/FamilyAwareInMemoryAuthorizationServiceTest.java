package io.github.oatelauser.jauth.starter;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oatelauser.jauth.core.token.InMemoryTokenFamilyService;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

/**
 * memory 模式 RTR 族谱熔断单测（与 core 的 RtrTokenFamilyTest 同语义、内存装配面）：轮转记族、重放探测命中即
 * 烧族——该 user+client 的全部授权一并失效、族谱标 BURNED，重放与烧后现役令牌都查无（框架报 invalid_grant）。
 *
 * @author oatelauser
 */
class FamilyAwareInMemoryAuthorizationServiceTest {

    private final InMemoryTokenFamilyService tokenFamilyService = new InMemoryTokenFamilyService();

    private final FamilyAwareInMemoryAuthorizationService authorizationService =
            new FamilyAwareInMemoryAuthorizationService(new InMemoryOAuth2AuthorizationService(), tokenFamilyService);

    @Test
    @DisplayName("save 记族：refresh 哈希入族谱，现行令牌可查")
    void saveRecordsFamily() {
        authorizationService.save(authorization("refresh-1", "access-1"));

        assertThat(authorizationService.findByToken("refresh-1", OAuth2TokenType.REFRESH_TOKEN))
                .isNotNull();
        assertThat(tokenFamilyService.findByRefreshTokenHash(
                        io.github.oatelauser.jauth.core.token.TokenHash.sha256Hex("refresh-1")))
                .isPresent();
    }

    @Test
    @DisplayName("重放熔断：旧代 refresh 再现 → 返回 null、整族授权删除、族谱 BURNED")
    void replayBurnsFamily() {
        OAuth2Authorization first = authorization("refresh-1", "access-1");
        authorizationService.save(first);
        // 轮转：同一授权换新 refresh（框架 save 路径）
        authorizationService.save(authorization("refresh-2", "access-2"));

        // 重放第一代
        assertThat(authorizationService.findByToken("refresh-1", OAuth2TokenType.REFRESH_TOKEN))
                .isNull();

        // 烧族后：现役第二代也一并失效（access/refresh 全查无）
        assertThat(authorizationService.findByToken("refresh-2", OAuth2TokenType.REFRESH_TOKEN))
                .isNull();
        assertThat(authorizationService.findByToken("access-2", OAuth2TokenType.ACCESS_TOKEN))
                .isNull();
        assertThat(tokenFamilyService
                        .findByRefreshTokenHash(io.github.oatelauser.jauth.core.token.TokenHash.sha256Hex("refresh-1"))
                        .orElseThrow()
                        .status())
                .isEqualTo(InMemoryTokenFamilyService.STATUS_BURNED);
    }

    @Test
    @DisplayName("烧族后重复重放幂等：探测仍命中（BURNED 行保留）、无异常")
    void repeatedReplayIsIdempotent() {
        authorizationService.save(authorization("refresh-1", "access-1"));
        authorizationService.save(authorization("refresh-2", "access-2"));
        assertThat(authorizationService.findByToken("refresh-1", OAuth2TokenType.REFRESH_TOKEN))
                .isNull();
        // 第二次重放：不抛、仍 null
        assertThat(authorizationService.findByToken("refresh-1", OAuth2TokenType.REFRESH_TOKEN))
                .isNull();
    }

    @Test
    @DisplayName("无关令牌不烧：从未见过的 refresh 返回 null 但不影响其他族/现役令牌")
    void unknownTokenDoesNotBurn() {
        authorizationService.save(authorization("refresh-1", "access-1"));

        assertThat(authorizationService.findByToken("never-seen", OAuth2TokenType.REFRESH_TOKEN))
                .isNull();
        assertThat(authorizationService.findByToken("refresh-1", OAuth2TokenType.REFRESH_TOKEN))
                .isNotNull();
    }

    private static OAuth2Authorization authorization(String refreshTokenValue, String accessTokenValue) {
        RegisteredClient registeredClient = RegisteredClient.withId("client-1")
                .clientId("client-1")
                .clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://example.com/cb")
                .scope("openid")
                .build();
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                accessTokenValue,
                Instant.now(),
                Instant.now().plusSeconds(3600));
        OAuth2RefreshToken refreshToken = new OAuth2RefreshToken(
                refreshTokenValue, Instant.now(), Instant.now().plusSeconds(86400));
        return OAuth2Authorization.withRegisteredClient(registeredClient)
                .id("authorization-1")
                .principalName("alice")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of("openid"))
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }
}
