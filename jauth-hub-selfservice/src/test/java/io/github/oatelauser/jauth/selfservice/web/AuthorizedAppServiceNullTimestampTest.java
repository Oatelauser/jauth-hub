package io.github.oatelauser.jauth.selfservice.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 授权看板查询对全 NULL 签发时间行的容忍回归（v1.2.1）：授权码被消费/过期后框架会把
 * oauth2_authorization 的三个 issued_at 列清空，此类残留行是合法库态——
 * {@code HashMap.merge} 不吃 null value，v1.2.1 前直接 NPE（用户真机报障）。钉死：
 * 该行仍进列表（client 不丢）、lastAuthorizedAt 落 null（视图以"—"渲染、排序 nullsLast）。
 *
 * <p>最小表影：只建服务实际读的七列（jdbc 侧无 Flyway 依赖，测的就是这段 SQL 的容错）。
 *
 * @author oatelauser
 */
class AuthorizedAppServiceNullTimestampTest {

    private AuthorizedAppService service;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setUrl("jdbc:h2:mem:authorized-apps-null-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.execute("CREATE TABLE oauth2_authorization ("
                + "id VARCHAR(100) NOT NULL, registered_client_id VARCHAR(100) NOT NULL,"
                + " principal_name VARCHAR(200), authorized_scopes VARCHAR(1000),"
                + " access_token_issued_at TIMESTAMP, authorization_code_issued_at TIMESTAMP,"
                + " refresh_token_issued_at TIMESTAMP)");
        this.service = new AuthorizedAppService(this.jdbc);
    }

    @Test
    @DisplayName("全 NULL 签发行不 NPE：client 保留、时间落 null、正常行时间不受扰")
    void allNullIssuedTimestampsTolerated() {
        this.jdbc.update("INSERT INTO oauth2_authorization VALUES ('a1', 'client-a', 'alice', 'openid profile',"
                + " TIMESTAMP '2026-10-01 10:00:00', NULL, NULL)");
        // 残留行：授权码已被消费/过期，三列 issued_at 全空
        this.jdbc.update(
                "INSERT INTO oauth2_authorization VALUES ('a2', 'client-b', 'alice', 'openid'," + " NULL, NULL, NULL)");

        List<AuthorizedApp> apps = this.service.list("alice");

        assertThat(apps).hasSize(2);
        assertThat(apps)
                .filteredOn(app -> "client-b".equals(app.registeredClientId()))
                .singleElement()
                .satisfies(app -> assertThat(app.lastAuthorizedAt()).isNull());
        assertThat(apps)
                .filteredOn(app -> "client-a".equals(app.registeredClientId()))
                .singleElement()
                // 与服务同源换算（Timestamp.toInstant 挂 JVM 默认时区，测试跨时区稳定）
                .satisfies(app -> assertThat(app.lastAuthorizedAt())
                        .isEqualTo(java.sql.Timestamp.valueOf("2026-10-01 10:00:00")
                                .toInstant()));
    }
}
