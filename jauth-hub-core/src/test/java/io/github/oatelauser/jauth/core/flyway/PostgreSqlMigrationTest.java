package io.github.oatelauser.jauth.core.flyway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oatelauser.jauth.core.support.IntegrationTestSupport;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL 真库迁移验证（Testcontainers）。
 *
 * <p>本机 Docker 不可用：{@code disabledWithoutDocker = true} 使该测试自动跳过（跳过原因出现在 测试报告），留 CI（有 Docker
 * 的环境）补跑——H2(PostgreSQL 模式) 已覆盖双兼容 SQL 的日常验证。
 *
 * @author oatelauser
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgreSqlMigrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void migratesAllExpectedTablesOnRealPostgreSql() throws SQLException {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(IntegrationTestSupport.FLYWAY_LOCATION)
                .load()
                .migrate();

        List<String> tables = new ArrayList<>();
        try (Statement statement = POSTGRES.createConnection("").createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT table_name FROM information_schema.tables WHERE"
                        + " table_schema = 'public' AND table_name <>"
                        + " 'flyway_schema_history'")) {
            while (resultSet.next()) {
                tables.add(resultSet.getString(1));
            }
        }
        assertThat(tables)
                .containsExactlyInAnyOrder(
                        "oauth2_registered_client",
                        "oauth2_authorization",
                        "oauth2_authorization_consent",
                        "jauth_user",
                        "jauth_user_credential",
                        "jauth_org",
                        "jauth_org_member",
                        "jauth_installation",
                        "jauth_pat",
                        "jauth_token_family",
                        "jauth_audit_event",
                        "jauth_jwk",
                        "spring_session",
                        "spring_session_attributes");
    }
}
