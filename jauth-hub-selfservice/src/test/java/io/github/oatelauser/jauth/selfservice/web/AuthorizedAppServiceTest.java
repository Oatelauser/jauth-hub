package io.github.oatelauser.jauth.selfservice.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.github.oatelauser.jauth.core.authorization.JauthJdbcOAuth2AuthorizationService;
import io.github.oatelauser.jauth.core.client.JauthJdbcRegisteredClientRepository;
import io.github.oatelauser.jauth.core.response.DefaultResponseRenderer;
import io.github.oatelauser.jauth.core.response.JauthErrorCode;
import io.github.oatelauser.jauth.core.response.JauthException;
import io.github.oatelauser.jauth.core.token.JdbcTokenFamilyService;
import io.github.oatelauser.jauth.core.util.UuidV7;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import io.github.oatelauser.jauth.core.web.PasskeyFlag;
import io.github.oatelauser.jauth.selfservice.support.IntegrationTestSupport;
import io.github.oatelauser.jauth.selfservice.support.Providers;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

/**
 * 看板服务集成测试（H2 + core Flyway）：按 principal 聚合列表 + 一键 Revoke 的双 remove 语义（授权行与
 * consent 行同时清掉，SPEC §5 横切"授权看板 + 一键 Revoke"）。
 *
 * @author oatelauser
 */
class AuthorizedAppServiceTest {

    /** 占位令牌值：非真实凭据。 */
    private static final String RAW_ACCESS_TOKEN = "placeholder-dashboard-token-not-real";

    private static final String PRINCIPAL = "alice";

    private static final String OTHER_PRINCIPAL = "bob";

    private JdbcTemplate jdbcTemplate;

    private JauthJdbcOAuth2AuthorizationService authorizationService;

    private JdbcOAuth2AuthorizationConsentService consentService;

    private AuthorizedAppService appService;

    private RegisteredClient firstClient;

    private RegisteredClient secondClient;

    @BeforeEach
    void setUp() {
        this.jdbcTemplate = IntegrationTestSupport.migratedJdbcTemplate("selfservice-apps");
        JauthJdbcRegisteredClientRepository clientRepository =
                new JauthJdbcRegisteredClientRepository(this.jdbcTemplate);
        this.authorizationService = new JauthJdbcOAuth2AuthorizationService(
                this.jdbcTemplate, clientRepository, new JdbcTokenFamilyService(this.jdbcTemplate));
        this.consentService = new JdbcOAuth2AuthorizationConsentService(this.jdbcTemplate, clientRepository);
        this.appService = new AuthorizedAppService(this.jdbcTemplate);
        this.firstClient = testClient("dashboard-client-a");
        this.secondClient = testClient("dashboard-client-b");
        clientRepository.save(this.firstClient);
        clientRepository.save(this.secondClient);
    }

    @Test
    void listGroupsByClientAndUnionsScopesForPrincipalOnly() {
        this.authorizationService.save(authorization(this.firstClient, PRINCIPAL, Set.of("openid"), "token-a-1"));
        this.authorizationService.save(
                authorization(this.firstClient, PRINCIPAL, Set.of("openid", "email"), "token-a-2"));
        this.authorizationService.save(
                authorization(this.secondClient, OTHER_PRINCIPAL, Set.of("profile"), "token-b-1"));

        List<AuthorizedApp> apps = this.appService.list(PRINCIPAL);

        assertThat(apps).hasSize(1);
        assertThat(apps.get(0).registeredClientId()).isEqualTo(this.firstClient.getId());
        assertThat(apps.get(0).scopes()).containsExactlyInAnyOrder("openid", "email");
        assertThat(apps.get(0).lastAuthorizedAt()).isNotNull();
        assertThat(this.appService.list(OTHER_PRINCIPAL))
                .extracting(AuthorizedApp::registeredClientId)
                .containsExactly(this.secondClient.getId());
    }

    @Test
    void revokeRemovesBothAuthorizationsAndConsent() {
        this.authorizationService.save(authorization(this.firstClient, PRINCIPAL, Set.of("openid"), "token-a-1"));
        this.authorizationService.save(authorization(this.firstClient, PRINCIPAL, Set.of("email"), "token-a-2"));
        this.consentService.save(OAuth2AuthorizationConsent.withId(this.firstClient.getId(), PRINCIPAL)
                .authority(new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_openid"))
                .build());
        this.authorizationService.save(authorization(this.secondClient, PRINCIPAL, Set.of("profile"), "token-b-1"));

        appsController().revoke(this.firstClient.getId(), () -> PRINCIPAL);

        // 双 remove：授权行清空（firstClient 的两行），consent 一并删除
        assertThat(this.authorizationService.findByToken("token-a-1", OAuth2TokenType.ACCESS_TOKEN))
                .isNull();
        assertThat(this.authorizationService.findByToken("token-a-2", OAuth2TokenType.ACCESS_TOKEN))
                .isNull();
        assertThat(this.consentService.findById(this.firstClient.getId(), PRINCIPAL))
                .isNull();
        // 其他 client 的授权不受波及
        assertThat(this.authorizationService.findByToken("token-b-1", OAuth2TokenType.ACCESS_TOKEN))
                .isNotNull();
        assertThat(this.appService.list(PRINCIPAL))
                .extracting(AuthorizedApp::registeredClientId)
                .containsExactly(this.secondClient.getId());
    }

    @Test
    void revokingNothingFailsWithNotFound() {
        assertThatThrownBy(() -> appsController().revoke(this.firstClient.getId(), () -> PRINCIPAL))
                .isInstanceOf(JauthException.class)
                .extracting(ex -> ((JauthException) ex).getCode())
                .isEqualTo(JauthErrorCode.B0502.getCode());
    }

    /** 真 jdbc 双服务 + 真 appService 的 revoke 编排入口（双 remove 语义由控制器编排，见其类注释）。 */
    private AuthorizedAppsController appsController() {
        return new AuthorizedAppsController(
                this.appService,
                Providers.fixed(this.authorizationService),
                Providers.fixed(this.consentService),
                mock(RegisteredClientRepository.class),
                EducationalFlag.ON,
                PasskeyFlag.OFF,
                new DefaultResponseRenderer());
    }

    private RegisteredClient testClient(String clientId) {
        return RegisteredClient.withId(UuidV7.generate().toString())
                .clientId(clientId)
                .clientName(clientId)
                .clientAuthenticationMethod(
                        org.springframework.security.oauth2.core.ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://placeholder.example.com/callback")
                .scope("openid")
                .scope("email")
                .scope("profile")
                .clientSettings(ClientSettings.builder().requireProofKey(true).build())
                .tokenSettings(TokenSettings.builder().build())
                .build();
    }

    private OAuth2Authorization authorization(
            RegisteredClient client, String principalName, Set<String> scopes, String rawAccessToken) {
        Instant issuedAt = Instant.parse("2026-09-29T10:00:00Z");
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, rawAccessToken, issuedAt, issuedAt.plusSeconds(7200), scopes);
        return OAuth2Authorization.withRegisteredClient(client)
                .id(UuidV7.generate().toString())
                .principalName(principalName)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(scopes)
                .token(accessToken)
                .build();
    }
}
