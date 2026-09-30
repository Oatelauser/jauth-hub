package io.github.oatelauser.jauth.selfservice.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oatelauser.jauth.core.client.JauthJdbcRegisteredClientRepository;
import io.github.oatelauser.jauth.selfservice.support.IntegrationTestSupport;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link JdbcOwnedAppService} 契约跑批 + DB 列级断言：owner 两列落位（B10 滑账①收口的 client+owner 双写）、
 * 平台内置行（owner 皆空）不进个人列表、事务模板在场时注册路径同成。
 *
 * @author oatelauser
 */
class JdbcOwnedAppServiceTest extends AbstractOwnedAppServiceContractTest {

    private final JdbcTemplate jdbcTemplate = IntegrationTestSupport.migratedJdbcTemplate("selfservice-owned-apps");

    private final JauthJdbcRegisteredClientRepository clientRepository =
            new JauthJdbcRegisteredClientRepository(this.jdbcTemplate);

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private final JdbcOwnedAppService service = new JdbcOwnedAppService(
            this.clientRepository, this.jdbcTemplate, null, this.passwordEncoder, scopeCatalog(), fixedClock());

    @Override
    protected OwnedAppService service() {
        return this.service;
    }

    @Override
    protected RegisteredClientRepository clientRepository() {
        return this.clientRepository;
    }

    @Override
    protected PasswordEncoder passwordEncoder() {
        return this.passwordEncoder;
    }

    @Test
    void ownerColumnsCarryUserIdAfterRegister() {
        OwnedAppService.Registration registration = this.service.register(USER_ID, "归属断言", REDIRECTS, true);

        String ownerUserId = this.jdbcTemplate.queryForObject(
                "SELECT owner_user_id FROM oauth2_registered_client WHERE client_id = ?",
                String.class,
                registration.app().clientId());
        assertThat(ownerUserId.trim()).isEqualTo(USER_ID);
    }

    @Test
    void platformBuiltInRowsStayOutOfPersonalList() {
        // 平台内置（owner 皆空）：列表按 owner_user_id 收紧，不可见
        this.jdbcTemplate.update("INSERT INTO oauth2_registered_client (id, client_id, client_name,"
                + " client_authentication_methods, authorization_grant_types, scopes,"
                + " client_settings, token_settings) VALUES ('builtin-owned-1', 'builtin-owned-client',"
                + " 'builtin', 'none', 'client_credentials', 'introspect', '{}', '{}')");
        this.service.register(USER_ID, "个人应用", REDIRECTS, false);

        assertThat(this.service.list(USER_ID))
                .extracting(OwnedAppService.OwnedApp::name)
                .containsExactly("个人应用");
    }

    @Test
    void registerPersistsBothWritesUnderTransactionTemplate() {
        // 滑账①收口的正路径：事务模板在场时 client+owner 双写同成（回滚面由 OrgService 同款模式测试背书）
        JdbcOwnedAppService transactional = new JdbcOwnedAppService(
                this.clientRepository,
                this.jdbcTemplate,
                new TransactionTemplate(new DataSourceTransactionManager(this.jdbcTemplate.getDataSource())),
                this.passwordEncoder,
                scopeCatalog(),
                fixedClock());

        OwnedAppService.Registration registration =
                transactional.register(USER_ID, "事务路径", Set.of("https://tx.example.com/cb"), true);

        String ownerUserId = this.jdbcTemplate.queryForObject(
                "SELECT owner_user_id FROM oauth2_registered_client WHERE client_id = ?",
                String.class,
                registration.app().clientId());
        assertThat(ownerUserId.trim()).isEqualTo(USER_ID);
    }
}
