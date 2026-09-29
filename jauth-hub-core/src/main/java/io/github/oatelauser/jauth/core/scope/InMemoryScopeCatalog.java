package io.github.oatelauser.jauth.core.scope;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.util.Assert;

/**
 * 默认内存目录：{@link ConcurrentHashMap} 承载注册与查找（p3c 并发章：容器选型用 j.u.c 并发容器，单键操作自身原子）， {@link #all()}
 * 每次取值快照后按名排序，避免把活容器泄漏出去被并发修改。
 *
 * @author oatelauser
 */
public class InMemoryScopeCatalog implements ScopeCatalog {

    private final Map<String, ScopeDefinition> scopes = new ConcurrentHashMap<>(16);

    public InMemoryScopeCatalog() {
        for (ScopeCatalog.Builtin builtin : ScopeCatalog.Builtin.values()) {
            register(builtin.definition());
        }
    }

    @Override
    public void register(ScopeDefinition definition) {
        Assert.notNull(definition, "definition cannot be null");
        scopes.put(definition.name(), definition);
    }

    @Override
    public Optional<ScopeDefinition> find(String name) {
        return Optional.ofNullable(scopes.get(name));
    }

    @Override
    public List<ScopeDefinition> all() {
        return scopes.values().stream()
                .sorted(Comparator.comparing(ScopeDefinition::name))
                .toList();
    }
}
