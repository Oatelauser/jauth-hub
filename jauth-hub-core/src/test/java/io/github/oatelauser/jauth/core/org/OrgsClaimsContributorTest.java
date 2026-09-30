package io.github.oatelauser.jauth.core.org;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oatelauser.jauth.core.token.TokenClaimsContext;
import io.github.oatelauser.jauth.core.user.JauthUser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * orgs claims 贡献者单测（B9）：归属条目形状（id/name/role，按 orgId 稳定排序）、查无用户与无归属不贡献键。
 *
 * @author oatelauser
 */
class OrgsClaimsContributorTest {

    private static final JauthUser ALICE = new JauthUser(
            "018f0000-0000-7000-8000-000000000001",
            "alice",
            "encoded-not-a-real-credential",
            null,
            null,
            JauthUser.ROLE_USER,
            JauthUser.STATUS_ACTIVE,
            null,
            Instant.parse("2026-01-01T00:00:00Z"));

    @Test
    void contributesOrgMembershipsAsIdNameRoleEntries() {
        OrgsClaimsContributor contributor = new OrgsClaimsContributor(
                this::lookupAlice,
                userId -> List.of(
                        new OrgMembership("org-b", "globex", OrgRole.MEMBER),
                        new OrgMembership("org-a", "acme", OrgRole.OWNER)));

        Map<String, Object> contributed =
                contributor.contribute(new TokenClaimsContext("alice", "rc-1", Set.of("openid")));

        assertThat(contributed).containsOnlyKeys(OrgsClaimsContributor.CLAIM_ORGS);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> orgs = (List<Map<String, String>>) contributed.get(OrgsClaimsContributor.CLAIM_ORGS);
        assertThat(orgs)
                .containsExactly(
                        Map.of("id", "org-a", "name", "acme", "role", "OWNER"),
                        Map.of("id", "org-b", "name", "globex", "role", "MEMBER"));
    }

    @Test
    void unknownUserContributesNothing() {
        OrgsClaimsContributor contributor = new OrgsClaimsContributor(this::lookupAlice, userId -> {
            throw new IllegalStateException("不应触达归属查询");
        });

        assertThat(contributor.contribute(new TokenClaimsContext("bob", "rc-1", Set.of("openid"))))
                .isEmpty();
    }

    @Test
    void userWithoutMembershipContributesNothing() {
        OrgsClaimsContributor contributor = new OrgsClaimsContributor(this::lookupAlice, userId -> List.of());

        assertThat(contributor.contribute(new TokenClaimsContext("alice", "rc-1", Set.of("openid"))))
                .isEmpty();
    }

    private JauthUser lookupAlice(String principalName) {
        return "alice".equals(principalName) ? ALICE : null;
    }
}
