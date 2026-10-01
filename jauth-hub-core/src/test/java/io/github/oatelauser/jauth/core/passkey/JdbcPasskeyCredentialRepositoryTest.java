package io.github.oatelauser.jauth.core.passkey;

import io.github.oatelauser.jauth.core.support.IntegrationTestSupport;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.JdbcUserRepository;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;

/**
 * JDBC 实现跑凭据仓储契约（H2 PostgreSQL 兼容模式，V8 迁移后 15 列全量读写；PG 侧由
 * PostgreSqlMigrationTest 验迁移）。
 *
 * @author oatelauser
 */
class JdbcPasskeyCredentialRepositoryTest extends AbstractPasskeyCredentialRepositoryContractTest {

    @Override
    protected UserCredentialRepository createRepository() {
        JdbcTemplate jdbcTemplate = IntegrationTestSupport.migratedJdbcTemplate("jauth-passkey-credential");
        // 外键 jauth_user_credential.user_id → jauth_user.id：先落契约测试的用户行
        new JdbcUserRepository(jdbcTemplate)
                .save(new JauthUser(
                        USER_ID,
                        "passkey-holder",
                        "{bcrypt}placeholder-not-a-real-hash",
                        null,
                        null,
                        JauthUser.ROLE_USER,
                        JauthUser.STATUS_ACTIVE,
                        null,
                        Instant.parse("2026-10-01T07:00:00Z")));
        return new JdbcPasskeyCredentialRepository(jdbcTemplate, this.auditPublisher);
    }
}
