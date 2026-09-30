package io.github.oatelauser.jauth.core.org;

import io.github.oatelauser.jauth.core.token.ClaimsContributor;
import io.github.oatelauser.jauth.core.token.TokenClaimsContext;
import io.github.oatelauser.jauth.core.user.JauthUser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * orgs claims 贡献者（SPEC §3 内省富化的 v1.1 平台层实现，B9 接线）：principal → 用户全部 org 归属 →
 * claim {@value #CLAIM_ORGS} = 每项 {id, name, role} 的列表——id 供业务资源服务器做 org 级鉴权，name 供展示，
 * role 供 org 内角色判定。经既有两个 OAuth2TokenCustomizer 接出，id_token / userinfo / opaque 内省三口径同形。
 *
 * <p>归属取<b>用户全部 membership</b>（不按 client 过滤）：发行 scope 的按组织封顶已由 ceiling 取交强制
 * （CeilingAware 装饰器）在授权侧完成，claims 侧是主体身份事实，不是授权结果。查无用户（如
 * client_credentials 主体）或无归属返回空 Map，不贡献键。
 *
 * <p>查找经 JDK 函数捕获（同 {@code DefaultClaimsContributor} 的 SpotBugs 取舍）；条目按 orgId 排序保证
 * 多次签发形状稳定。
 *
 * @author oatelauser
 */
public class OrgsClaimsContributor implements ClaimsContributor {

    /** claims 键名：orgs（用户归属组织列表）。 */
    static final String CLAIM_ORGS = "orgs";

    private final Function<String, @Nullable JauthUser> userLookup;

    private final Function<String, List<OrgMembership>> membershipsByUser;

    public OrgsClaimsContributor(
            Function<String, @Nullable JauthUser> userLookup, Function<String, List<OrgMembership>> membershipsByUser) {
        this.userLookup = userLookup;
        this.membershipsByUser = membershipsByUser;
    }

    @Override
    public Map<String, Object> contribute(TokenClaimsContext context) {
        JauthUser user = this.userLookup.apply(context.principalName());
        if (user == null) {
            return Map.of();
        }
        List<OrgMembership> memberships = this.membershipsByUser.apply(user.id());
        if (memberships.isEmpty()) {
            return Map.of();
        }
        // 值类型用 ArrayList/LinkedHashMap 而非 List.of/Map.of：opaque claims 会随授权行落库（jdbc），
        // spring-security 的 Jackson 多态白名单在回读时拒 JDK 不可变集合（ImmutableCollections$ListN）
        List<Map<String, String>> orgs = new ArrayList<>(memberships.size());
        for (OrgMembership membership : memberships.stream()
                .sorted(Comparator.comparing(OrgMembership::orgId))
                .toList()) {
            Map<String, String> entry = new LinkedHashMap<>(4);
            entry.put("id", membership.orgId());
            entry.put("name", membership.orgName());
            entry.put("role", membership.role().name());
            orgs.add(entry);
        }
        return Map.of(CLAIM_ORGS, orgs);
    }
}
