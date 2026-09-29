package io.github.oatelauser.jauth.core.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * {@link InMemoryUserRepository} 契约 + 内存特有行为（同名拒绝）测试。
 *
 * @author oatelauser
 */
class InMemoryUserRepositoryTest extends AbstractUserRepositoryContractTest {

    private final InMemoryUserRepository repository = new InMemoryUserRepository();

    @Override
    protected UserRepository createRepository() {
        return this.repository;
    }

    @Test
    void rejectsSecondUserWithSameUsername() {
        repository().save(newUser("u-10", "eve"));
        assertThatThrownBy(() -> repository().save(newUser("u-11", "eve")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eve");
    }

    @Test
    void allowsSavingSameUserTwiceIdempotently() {
        JauthUser user = newUser("u-12", "frank");
        repository().save(user);
        repository().save(user);
        assertThat(repository().findByUsername("frank")).isEqualTo(user);
    }
}
