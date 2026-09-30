package io.github.oatelauser.jauth.core.org;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.util.Assert;

/**
 * {@link InstallationRepository} 的内存实现：memory 存储模式（demo/嵌入轻量场景）使用，重启即失。
 *
 * <p>save 不重复实现 (client, org) 唯一键：服务层 request 只在无既有行时走 save（重发分流到 update），
 * 单机内存模式无并发撞键面；DB 约束兜底语义由 jdbc 侧承担。
 *
 * @author oatelauser
 */
public class InMemoryInstallationRepository implements InstallationRepository {

    private final Map<String, Installation> installationsById = new ConcurrentHashMap<>();

    @Override
    public @Nullable Installation findById(String id) {
        Assert.hasText(id, "id cannot be empty");
        return this.installationsById.get(id);
    }

    @Override
    public @Nullable Installation findByClientAndOrg(String registeredClientId, String orgId) {
        Assert.hasText(registeredClientId, "registeredClientId cannot be empty");
        Assert.hasText(orgId, "orgId cannot be empty");
        return this.installationsById.values().stream()
                .filter(installation -> installation.registeredClientId().equals(registeredClientId)
                        && installation.orgId().equals(orgId))
                .findFirst()
                .orElse(null);
    }

    @Override
    public List<Installation> findByClient(String registeredClientId) {
        Assert.hasText(registeredClientId, "registeredClientId cannot be empty");
        return this.installationsById.values().stream()
                .filter(installation -> installation.registeredClientId().equals(registeredClientId))
                .toList();
    }

    @Override
    public List<Installation> findByOrg(String orgId) {
        Assert.hasText(orgId, "orgId cannot be empty");
        // 排序口径与 jdbc 实现对齐（createdAt DESC, id ASC）：ConcurrentHashMap 值序不定，页面渲染需确定序
        return this.installationsById.values().stream()
                .filter(installation -> installation.orgId().equals(orgId))
                .sorted(Comparator.comparing(Installation::createdAt, Comparator.reverseOrder())
                        .thenComparing(Installation::id))
                .toList();
    }

    @Override
    public void save(Installation installation) {
        Assert.notNull(installation, "installation cannot be null");
        this.installationsById.put(installation.id(), installation);
    }

    @Override
    public void update(Installation installation) {
        Assert.notNull(installation, "installation cannot be null");
        this.installationsById.put(installation.id(), installation);
    }
}
