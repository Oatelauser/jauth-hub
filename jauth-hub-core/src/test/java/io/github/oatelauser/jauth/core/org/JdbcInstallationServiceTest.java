package io.github.oatelauser.jauth.core.org;

import io.github.oatelauser.jauth.core.audit.AuditEventPublisher;
import io.github.oatelauser.jauth.core.support.IntegrationTestSupport;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link InstallationService} JDBC 实现契约测试：H2(PostgreSQL 模式) + Flyway 真库；requested_by/approved_by
 * 外键所需的 jauth_user 种子行与客户端存在性所需的 oauth2_registered_client 种子行先行落库。
 *
 * @author oatelauser
 */
class JdbcInstallationServiceTest extends AbstractInstallationServiceContractTest {

    /** 占位密码哈希：非真实凭据，仅外键种子用。 */
    private static final String PLACEHOLDER_HASH = "{bcrypt}placeholder-not-a-real-hash";

    private final JdbcTemplate jdbcTemplate = IntegrationTestSupport.migratedJdbcTemplate("jauth-installation-service");

    JdbcInstallationServiceTest() {
        seedUser(OWNER_ID, "install-owner");
        seedUser(MEMBER_ID, "install-member");
        seedUser(OUTSIDER_ID, "install-outsider");
        // findByOrg 契约需同 org 多行多状态：(client, org) 唯一键要求行间 client 互异，另播两枚种子
        seedClient(CLIENT_ID);
        seedClient("install-client-other-1");
        seedClient("install-client-other-2");
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

    private void seedUser(String id, String username) {
        this.jdbcTemplate.update(
                "INSERT INTO jauth_user (id, username, password_hash, role, status, created_at)"
                        + " VALUES (?, ?, ?, 'USER', 'ACTIVE', CURRENT_TIMESTAMP)",
                id,
                username,
                PLACEHOLDER_HASH);
    }

    private void seedClient(String registeredClientId) {
        this.jdbcTemplate.update(
                "INSERT INTO oauth2_registered_client (id, client_id, client_name,"
                        + " client_authentication_methods, authorization_grant_types, scopes,"
                        + " client_settings, token_settings) VALUES (?, ?, 'install-app', 'none',"
                        + " 'client_credentials', 'openid', '{}', '{}')",
                registeredClientId,
                registeredClientId);
    }
}
