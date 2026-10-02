package io.github.oatelauser.jauth.core.org;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.util.Assert;

/**
 * {@link OrgRepository} 的内存实现：memory 存储模式（demo/嵌入轻量场景）使用，重启即失。
 *
 * <p>findByName/findMembershipsByUser 采用线性扫描：本实现面向 demo 规模，不值得维护二级索引（取舍同
 * {@code InMemoryUserRepository}）。save 对重名抛 {@link DuplicateKeyException}（{@code DataIntegrityViolationException}
 * 子类）——与 jdbc 侧唯一约束同语义，服务层的约束兜底 catch 因此对两实现单点生效。
 *
 * @author oatelauser
 */
public class InMemoryOrgRepository implements OrgRepository {

    private final Map<String, Org> orgsById = new ConcurrentHashMap<>();

    /** 键 = orgId + "|" + userId（联合主键的字符串拼接视图）。 */
    private final Map<String, OrgMember> membersByKey = new ConcurrentHashMap<>();

    @Override
    public @Nullable Org findById(String id) {
        Assert.hasText(id, "id cannot be empty");
        return this.orgsById.get(id);
    }

    @Override
    public @Nullable Org findByName(String name) {
        Assert.hasText(name, "name cannot be empty");
        return this.orgsById.values().stream()
                .filter(org -> org.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    @Override
    public void save(Org org) {
        Assert.notNull(org, "org cannot be null");
        Org existing = findByName(org.name());
        if (existing != null && !existing.id().equals(org.id())) {
            throw new DuplicateKeyException("Org name already exists: " + org.name());
        }
        this.orgsById.put(org.id(), org);
    }

    @Override
    public void saveMember(OrgMember member) {
        Assert.notNull(member, "member cannot be null");
        this.membersByKey.put(memberKey(member.orgId(), member.userId()), member);
    }

    @Override
    public @Nullable OrgMember findMember(String orgId, String userId) {
        Assert.hasText(orgId, "orgId cannot be empty");
        Assert.hasText(userId, "userId cannot be empty");
        return this.membersByKey.get(memberKey(orgId, userId));
    }

    @Override
    public List<OrgMembership> findMembershipsByUser(String userId) {
        Assert.hasText(userId, "userId cannot be empty");
        // 本实现无 org 删除路径，成员行不会先于 org 消失；org 缺失属状态损坏，直接 NPE 暴露优于静默空名
        return this.membersByKey.values().stream()
                .filter(member -> member.userId().equals(userId))
                .map(member -> new OrgMembership(
                        member.orgId(), this.orgsById.get(member.orgId()).name(), member.role()))
                .toList();
    }

    @Override
    public List<OrgMember> findMembersByOrg(String orgId) {
        Assert.hasText(orgId, "orgId cannot be empty");
        return this.membersByKey.values().stream()
                .filter(member -> member.orgId().equals(orgId))
                .toList();
    }

    @Override
    public void deleteMember(String orgId, String userId) {
        Assert.hasText(orgId, "orgId cannot be empty");
        Assert.hasText(userId, "userId cannot be empty");
        this.membersByKey.remove(memberKey(orgId, userId));
    }

    @Override
    public void updateMemberRole(String orgId, String userId, OrgRole role) {
        Assert.hasText(orgId, "orgId cannot be empty");
        Assert.hasText(userId, "userId cannot be empty");
        Assert.notNull(role, "role cannot be null");
        OrgMember existing = this.membersByKey.get(memberKey(orgId, userId));
        if (existing != null) {
            this.membersByKey.put(memberKey(orgId, userId), new OrgMember(orgId, userId, role, existing.createdAt()));
        }
    }

    private static String memberKey(String orgId, String userId) {
        return orgId + "|" + userId;
    }
}
