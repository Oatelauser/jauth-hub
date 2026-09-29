package io.github.oatelauser.jauth.core.token;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oatelauser.jauth.core.user.JauthUser;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 默认 claims 贡献者单测：sub=用户 id、username=登录名；查无用户（客户端主体等）不贡献。
 *
 * @author oatelauser
 */
class DefaultClaimsContributorTest {

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

    @Test
    void contributesSubAndUsernameForKnownPrincipal() {
        DefaultClaimsContributor contributor =
                new DefaultClaimsContributor(principal -> "alice".equals(principal) ? ALICE : null);

        Map<String, Object> claims =
                contributor.contribute(new TokenClaimsContext("alice", "client-a", Set.of("openid")));

        assertThat(claims)
                .containsEntry(DefaultClaimsContributor.CLAIM_SUB, ALICE.id())
                .containsEntry(DefaultClaimsContributor.CLAIM_USERNAME, "alice");
    }

    @Test
    void unknownPrincipalContributesNothing() {
        DefaultClaimsContributor contributor = new DefaultClaimsContributor(principal -> null);

        Map<String, Object> claims =
                contributor.contribute(new TokenClaimsContext("stranger", "client-a", Set.of("openid")));

        assertThat(claims).isEmpty();
    }
}
