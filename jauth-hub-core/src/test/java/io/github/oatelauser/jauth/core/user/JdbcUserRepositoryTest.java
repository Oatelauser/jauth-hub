package io.github.oatelauser.jauth.core.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.oatelauser.jauth.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@link JdbcUserRepository} 契约 + JDBC 特有行为（唯一约束）测试：H2(PostgreSQL 模式) + Flyway 真库。
 *
 * @author oatelauser
 */
class JdbcUserRepositoryTest extends AbstractUserRepositoryContractTest {

    private final UserRepository repository =
            new JdbcUserRepository(IntegrationTestSupport.migratedJdbcTemplate("jauth-user-repo"));

    @Override
    protected UserRepository createRepository() {
        return this.repository;
    }

    @Test
    void uniqueUsernameConstraintRejectsDuplicate() {
        repository().save(newUser("018f0000-0000-7000-8000-000000000001", "grace"));
        assertThatThrownBy(() -> repository().save(newUser("018f0000-0000-7000-8000-000000000002", "grace")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(repository().findByUsername("grace")).isNotNull();
    }
}
