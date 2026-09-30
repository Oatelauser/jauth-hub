package io.github.oatelauser.jauth.core.org;

import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.util.Assert;

/**
 * 组织客户端的 scope 封门口径（SPEC §3：发行 scopes = 请求 ∩ consent ∩ installation.ceiling）：
 * "候选 org 集 = 用户 membership ∩ 该 client 的 APPROVED 安装"与"指定 org 的 ceiling 读取"两个查询的
 * 单点实现，服务端强制（core authorization 的 CeilingAware 装饰器）与 consent 页 UX（core web 的
 * ConsentController）共用同一口径——两侧注释互指，改口径只改这里。
 *
 * <p>fail-closed 语义在装饰器侧：候选为 0 或多 org 无会话暂存选择时拒绝保存，本类只做查询不做判定。
 *
 * @author oatelauser
 */
public class OrgScopeGate {

    private final UserRepository userRepository;

    private final OrgRepository orgRepository;

    private final InstallationRepository installationRepository;

    public OrgScopeGate(
            UserRepository userRepository, OrgRepository orgRepository, InstallationRepository installationRepository) {
        this.userRepository = userRepository;
        this.orgRepository = orgRepository;
        this.installationRepository = installationRepository;
    }

    /**
     * 查用户在该组织客户端上的候选 org 集（含角色与 org 名）：用户归属的 org 中，与该 client 存在
     * APPROVED 安装的那些。查无此用户（如客户端主体）返回空列表——组织客户端的用户授权主体必须是真实用户。
     *
     * @param principalName 登录名（框架 principal name）
     * @param registeredClientId oauth2_registered_client.id
     * @return 候选归属条目（无候选为空列表）
     */
    public List<OrgMembership> approvedOrgs(String principalName, String registeredClientId) {
        Assert.hasText(principalName, "principalName cannot be empty");
        Assert.hasText(registeredClientId, "registeredClientId cannot be empty");
        JauthUser user = this.userRepository.findByUsername(principalName);
        if (user == null) {
            return List.of();
        }
        // 单查该 client 全部安装行再内存筛：逐 org 查库是循环内 DB 查询（p3c 强制项），memberships 数虽小也不留口径
        Set<String> approvedOrgIds = this.installationRepository.findByClient(registeredClientId).stream()
                .filter(installation -> installation.status() == InstallationStatus.APPROVED)
                .map(Installation::orgId)
                .collect(Collectors.toUnmodifiableSet());
        return this.orgRepository.findMembershipsByUser(user.id()).stream()
                .filter(membership -> approvedOrgIds.contains(membership.orgId()))
                .toList();
    }

    /**
     * 读指定 org 的 ceiling scopes。
     *
     * @param registeredClientId oauth2_registered_client.id
     * @param orgId org id
     * @return ceiling scopes；该 (client, org) 无 APPROVED 安装返回 null
     */
    public @Nullable Set<String> ceilingScopes(String registeredClientId, String orgId) {
        Assert.hasText(registeredClientId, "registeredClientId cannot be empty");
        Assert.hasText(orgId, "orgId cannot be empty");
        Installation installation = this.installationRepository.findByClientAndOrg(registeredClientId, orgId);
        return installation != null && installation.status() == InstallationStatus.APPROVED
                ? installation.ceilingScopes()
                : null;
    }
}
