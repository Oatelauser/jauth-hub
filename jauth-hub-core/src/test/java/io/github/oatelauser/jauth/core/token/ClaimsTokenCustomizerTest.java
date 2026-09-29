package io.github.oatelauser.jauth.core.token;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oatelauser.jauth.core.user.JauthUser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenClaimsContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenClaimsSet;

/**
 * 两个 OAuth2TokenCustomizer 消费面单测：opaque access token claims（内省端点读取）与 OIDC id_token 编码——同一批 {@link
 * ClaimsContributor} 在两个面产出一致的键值。
 *
 * @author oatelauser
 */
class ClaimsTokenCustomizerTest {

    private static final JauthUser ALICE = new JauthUser(
            "018f0000-0000-7000-8000-000000000001",
            "alice",
            "encoded-not-a-real-credential",
            "Alice",
            null,
            JauthUser.ROLE_USER,
            JauthUser.STATUS_ACTIVE,
            null,
            Instant.parse("2026-01-01T00:00:00Z"));

    private RegisteredClient registeredClient;

    @BeforeEach
    void setUp() {
        this.registeredClient = RegisteredClient.withId("rc-1")
                .clientId("client-a")
                .clientName("client-a")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://placeholder.example.com/callback")
                .scope("openid")
                .build();
    }

    @Test
    void opaqueAccessTokenReceivesContributedClaims() {
        OAuth2TokenClaimsSet.Builder claimsSet = OAuth2TokenClaimsSet.builder();
        OAuth2TokenClaimsContext context = OAuth2TokenClaimsContext.with(claimsSet)
                .registeredClient(registeredClient)
                .principal(new TestingAuthenticationToken("alice", "n/a"))
                .authorizedScopes(Set.of("openid"))
                .build();

        new OpaqueAccessTokenCustomizer(List.of(new DefaultClaimsContributor(this::lookupAlice))).customize(context);

        Map<String, Object> claims = claimsSet.build().getClaims();
        assertThat(claims).containsEntry(DefaultClaimsContributor.CLAIM_SUB, ALICE.id());
        assertThat(claims).containsEntry(DefaultClaimsContributor.CLAIM_USERNAME, "alice");
    }

    @Test
    void idTokenEncodingReceivesContributedClaims() {
        JwtClaimsSet.Builder claimsSet = JwtClaimsSet.builder();
        JwtEncodingContext context = JwtEncodingContext.with(JwsHeader.with(SignatureAlgorithm.RS256), claimsSet)
                .registeredClient(registeredClient)
                .principal(new TestingAuthenticationToken("alice", "n/a"))
                .authorizedScopes(Set.of("openid"))
                .tokenType(new OAuth2TokenType(OidcParameterNames.ID_TOKEN))
                .build();

        new OidcIdTokenCustomizer(List.of(new DefaultClaimsContributor(this::lookupAlice))).customize(context);

        Map<String, Object> claims = claimsSet.build().getClaims();
        assertThat(claims).containsEntry(DefaultClaimsContributor.CLAIM_SUB, ALICE.id());
        assertThat(claims).containsEntry(DefaultClaimsContributor.CLAIM_USERNAME, "alice");
    }

    @Test
    void nonIdTokenJwtEncodingIsLeftAlone() {
        JwtClaimsSet.Builder claimsSet = JwtClaimsSet.builder().subject("alice");
        JwtEncodingContext context = JwtEncodingContext.with(JwsHeader.with(SignatureAlgorithm.RS256), claimsSet)
                .registeredClient(registeredClient)
                .principal(new TestingAuthenticationToken("alice", "n/a"))
                .authorizedScopes(Set.of("openid"))
                .tokenType(OAuth2TokenType.ACCESS_TOKEN)
                .build();

        new OidcIdTokenCustomizer(List.of(new DefaultClaimsContributor(this::lookupAlice))).customize(context);

        assertThat(claimsSet.build().getClaims()).doesNotContainKey(DefaultClaimsContributor.CLAIM_USERNAME);
    }

    private JauthUser lookupAlice(String principalName) {
        return "alice".equals(principalName) ? ALICE : null;
    }
}
