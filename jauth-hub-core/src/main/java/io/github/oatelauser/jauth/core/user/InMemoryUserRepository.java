package io.github.oatelauser.jauth.core.user;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.springframework.util.Assert;

/**
 * {@link UserRepository} 的内存实现：memory 存储模式（demo/嵌入轻量场景）使用，重启即失。
 *
 * <p>findByUsername 采用线性扫描：本实现面向 demo 规模（几十用户），不值得维护二级索引。
 *
 * @author oatelauser
 */
public class InMemoryUserRepository implements UserRepository {

    private final Map<String, JauthUser> usersById = new ConcurrentHashMap<>();

    @Override
    public @Nullable JauthUser findById(String id) {
        Assert.hasText(id, "id cannot be empty");
        return this.usersById.get(id);
    }

    @Override
    public @Nullable JauthUser findByUsername(String username) {
        Assert.hasText(username, "username cannot be empty");
        return this.usersById.values().stream()
                .filter(user -> user.username().equals(username))
                .findFirst()
                .orElse(null);
    }

    @Override
    public void save(JauthUser user) {
        Assert.notNull(user, "user cannot be null");
        JauthUser existing = findByUsername(user.username());
        if (existing != null && !existing.id().equals(user.id())) {
            throw new IllegalArgumentException("Username already exists: " + user.username());
        }
        this.usersById.put(user.id(), user);
    }

    @Override
    public void updatePasswordHash(String id, String passwordHash) {
        Assert.hasText(id, "id cannot be empty");
        Assert.hasText(passwordHash, "passwordHash cannot be empty");
        update(
                id,
                user -> new JauthUser(
                        user.id(),
                        user.username(),
                        passwordHash,
                        user.displayName(),
                        user.email(),
                        user.role(),
                        user.status(),
                        user.strongAuthAt(),
                        user.createdAt()));
    }

    @Override
    public void updateStatus(String id, String status) {
        Assert.hasText(id, "id cannot be empty");
        Assert.hasText(status, "status cannot be empty");
        update(
                id,
                user -> new JauthUser(
                        user.id(),
                        user.username(),
                        user.passwordHash(),
                        user.displayName(),
                        user.email(),
                        user.role(),
                        status,
                        user.strongAuthAt(),
                        user.createdAt()));
    }

    @Override
    public void updateStrongAuthAt(String id, @Nullable Instant strongAuthAt) {
        Assert.hasText(id, "id cannot be empty");
        update(
                id,
                user -> new JauthUser(
                        user.id(),
                        user.username(),
                        user.passwordHash(),
                        user.displayName(),
                        user.email(),
                        user.role(),
                        user.status(),
                        strongAuthAt,
                        user.createdAt()));
    }

    private void update(String id, UnaryOperator<JauthUser> mapper) {
        this.usersById.computeIfPresent(id, (key, user) -> mapper.apply(user));
    }
}
