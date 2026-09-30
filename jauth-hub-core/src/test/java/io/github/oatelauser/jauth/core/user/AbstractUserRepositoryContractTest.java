package io.github.oatelauser.jauth.core.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 用户仓储契约测试（抽象基类）：同一套断言跑 InMemory 与 JDBC 两实现，保证行为一致。 命名遵守 p3c 抽象类前缀 Abstract 约定。
 *
 * @author oatelauser
 */
abstract class AbstractUserRepositoryContractTest {

    /** 占位密码哈希：非真实凭据，仅契约验证用。 */
    protected static final String PLACEHOLDER_HASH = "{bcrypt}placeholder-not-a-real-hash";

    private UserRepository repository;

    protected abstract UserRepository createRepository();

    @BeforeEach
    void setUp() {
        this.repository = createRepository();
    }

    @Test
    void saveThenFindByIdAndUsername() {
        JauthUser user = newUser("018f0000-0000-7000-8000-000000000001", "alice");
        repository().save(user);

        assertThat(repository().findById(user.id())).isEqualTo(user);
        assertThat(repository().findByUsername("alice")).isEqualTo(user);
    }

    @Test
    void findMissingReturnsNull() {
        assertThat(repository().findById("00000000-0000-7000-8000-000000000000"))
                .isNull();
        assertThat(repository().findByUsername("nobody")).isNull();
    }

    @Test
    void updatePasswordHashAffectsOnlyThatField() {
        JauthUser user = newUser("018f0000-0000-7000-8000-000000000002", "bob");
        repository().save(user);

        repository().updatePasswordHash(user.id(), "{bcrypt}rotated-placeholder");

        JauthUser updated = repository().findById(user.id());
        assertThat(updated.passwordHash()).isEqualTo("{bcrypt}rotated-placeholder");
        assertThat(updated.username()).isEqualTo("bob");
        assertThat(updated.role()).isEqualTo(JauthUser.ROLE_USER);
    }

    @Test
    void updateStatusAffectsOnlyThatField() {
        JauthUser user = newUser("018f0000-0000-7000-8000-000000000003", "carol");
        repository().save(user);

        repository().updateStatus(user.id(), JauthUser.STATUS_DISABLED);

        JauthUser updated = repository().findById(user.id());
        assertThat(updated.status()).isEqualTo(JauthUser.STATUS_DISABLED);
        assertThat(updated.passwordHash()).isEqualTo(PLACEHOLDER_HASH);
    }

    @Test
    void updateStrongAuthAtSetsAndClears() {
        JauthUser user = newUser("018f0000-0000-7000-8000-000000000004", "dave");
        repository().save(user);
        Instant strongAuthAt = Instant.parse("2026-09-29T10:15:30Z");

        repository().updateStrongAuthAt(user.id(), strongAuthAt);
        assertThat(repository().findById(user.id()).strongAuthAt()).isEqualTo(strongAuthAt);

        repository().updateStrongAuthAt(user.id(), null);
        assertThat(repository().findById(user.id()).strongAuthAt()).isNull();
    }

    @Test
    void updateUnknownIdIsSilentlyIgnored() {
        repository().updateStatus("00000000-0000-7000-8000-000000000001", JauthUser.STATUS_DISABLED);
        assertThat(repository().findByUsername("nobody")).isNull();
    }

    @Test
    void updateRoleAffectsOnlyThatField() {
        JauthUser user = newUser("018f0000-0000-7000-8000-000000000005", "erin");
        repository().save(user);

        repository().updateRole(user.id(), JauthUser.ROLE_SUPERADMIN);

        JauthUser updated = repository().findById(user.id());
        assertThat(updated.role()).isEqualTo(JauthUser.ROLE_SUPERADMIN);
        assertThat(updated.status()).isEqualTo(JauthUser.STATUS_ACTIVE);
        assertThat(updated.displayName()).isEqualTo("erin-display");
    }

    @Test
    void updateDisplayNameSetsAndClears() {
        JauthUser user = newUser("018f0000-0000-7000-8000-000000000006", "frank");
        repository().save(user);

        repository().updateDisplayName(user.id(), "新展示名");
        assertThat(repository().findById(user.id()).displayName()).isEqualTo("新展示名");

        repository().updateDisplayName(user.id(), null);
        assertThat(repository().findById(user.id()).displayName()).isNull();
        assertThat(repository().findById(user.id()).username()).isEqualTo("frank");
    }

    @Test
    void findAllReturnsAllOrderedByUsernameAsc() {
        repository().save(newUser("018f0000-0000-7000-8000-000000000007", "zoe"));
        repository().save(newUser("018f0000-0000-7000-8000-000000000008", "alice"));
        repository().save(newUser("018f0000-0000-7000-8000-000000000009", "mallory"));

        assertThat(repository().findAll()).extracting(JauthUser::username).containsExactly("alice", "mallory", "zoe");
    }

    protected final UserRepository repository() {
        return this.repository;
    }

    protected final JauthUser newUser(String id, String username) {
        return new JauthUser(
                id,
                username,
                PLACEHOLDER_HASH,
                username + "-display",
                null,
                JauthUser.ROLE_USER,
                JauthUser.STATUS_ACTIVE,
                null,
                Instant.parse("2026-09-29T08:00:00Z"));
    }
}
