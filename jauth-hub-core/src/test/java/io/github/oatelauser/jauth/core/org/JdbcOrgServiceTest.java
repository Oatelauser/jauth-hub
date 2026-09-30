package io.github.oatelauser.jauth.core.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.oatelauser.jauth.core.audit.AuditEventPublisher;
import io.github.oatelauser.jauth.core.support.IntegrationTestSupport;
import io.github.oatelauser.jauth.core.util.UuidV7;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link OrgService} + {@link JdbcOrgRepository}/{@link JdbcInstallationRepository} 契约测试：H2(PostgreSQL
 * 模式) + Flyway 真库；成员/审批人外键所需的 jauth_user 种子行先行落库。
 *
 * @author oatelauser
 */
class JdbcOrgServiceTest extends AbstractOrgServiceContractTest {

    /** 占位密码哈希：非真实凭据，仅外键种子用。 */
    private static final String PLACEHOLDER_HASH = "{bcrypt}placeholder-not-a-real-hash";

    private final JdbcTemplate jdbcTemplate = IntegrationTestSupport.migratedJdbcTemplate("jauth-org-service");

    JdbcOrgServiceTest() {
        seedUser(OWNER_ID, "org-owner");
        seedUser(MEMBER_ID, "org-member");
        seedUser(OUTSIDER_ID, "org-outsider");
    }

    @Override
    protected OrgDomainFixture createFixture() {
        AuditEventPublisher recorder = this.auditLog::add;
        JdbcOrgRepository orgRepository = new JdbcOrgRepository(this.jdbcTemplate);
        OrgService orgService = new OrgService(orgRepository, recorder, this.clock, null);
        JdbcInstallationRepository installationRepository = new JdbcInstallationRepository(this.jdbcTemplate);
        return new OrgDomainFixture(
                orgRepository,
                installationRepository,
                orgService,
                new InstallationService(
                        installationRepository, orgRepository, orgService, this.clientStub, recorder, this.clock));
    }

    @Test
    void memberWriteFailureRollsBackOrgUnderTransactionTemplate() {
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(new DataSourceTransactionManager(this.jdbcTemplate.getDataSource()));
        // 手工桩：成员写失败（B10 滑账①收口的回滚面——org 落库须随事务一并撤销）
        OrgRepository failingMemberRepository = new JdbcOrgRepository(this.jdbcTemplate) {
            @Override
            public void saveMember(OrgMember member) {
                throw new IllegalStateException("member write failed (simulated)");
            }
        };
        OrgService service = new OrgService(failingMemberRepository, event -> {}, this.clock, transactionTemplate);

        assertThatThrownBy(() -> service.create("rollback-org", OWNER_ID)).isInstanceOf(IllegalStateException.class);
        Integer orgRows = this.jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM jauth_org WHERE name = 'rollback-org'", Integer.class);
        assertThat(orgRows).as("member 写失败时 org 不应残留（无主 org 回滚）").isZero();
    }

    @Test
    void nameUniqueConstraintBackstopsServicePrecheck() {
        // 服务层先查后插的并发窗口兜底：DB 唯一约束确实拦截重名（jdbc 特有行为）
        OrgRepository repository = fixture().orgRepository();
        repository.save(new Org(UuidV7.generate().toString(), "acme", FIXED_NOW));
        assertThatThrownBy(() -> repository.save(new Org(UuidV7.generate().toString(), "acme", FIXED_NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void seedUser(String id, String username) {
        this.jdbcTemplate.update(
                "INSERT INTO jauth_user (id, username, password_hash, role, status, created_at)"
                        + " VALUES (?, ?, ?, 'USER', 'ACTIVE', CURRENT_TIMESTAMP)",
                id,
                username,
                PLACEHOLDER_HASH);
    }
}
