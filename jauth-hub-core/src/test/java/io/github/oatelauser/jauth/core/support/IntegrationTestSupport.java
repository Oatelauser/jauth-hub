package io.github.oatelauser.jauth.core.support;

import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.Driver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * 集成测试支撑：H2（PostgreSQL 兼容模式）内存库 + 自有命名空间 Flyway 迁移，一步到位产出 {@link JdbcTemplate}。每个测试用独立库名，互不串扰。
 *
 * @author oatelauser
 */
public final class IntegrationTestSupport {

    /** 库迁移脚本位置：非默认 db/migration——嵌入宿主有自己的 Flyway，必须走自有命名空间（SPEC 决议）。 */
    public static final String FLYWAY_LOCATION = "classpath:io/github/oatelauser/jauth/core/flyway";

    /** MODE=PostgreSQL：H2 按 PG 方言语义执行，验证双兼容 SQL 的 PG 侧。 */
    private static final String H2_URL_TEMPLATE =
            "jdbc:h2:mem:%s;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";

    /** DB_CLOSE_DELAY=-1 使库跨连接存活，故每次调用追加序号保证测试实例间互不串库。 */
    private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();

    private IntegrationTestSupport() {}

    /**
     * 建库并跑完全部迁移。
     *
     * @param databaseName 内存库名基数（自动追加唯一序号）
     * @return 迁移完成后的 JdbcTemplate
     */
    public static JdbcTemplate migratedJdbcTemplate(String databaseName) {
        String uniqueName = databaseName + "-" + DATABASE_SEQUENCE.incrementAndGet();
        DataSource dataSource =
                new SimpleDriverDataSource(new Driver(), H2_URL_TEMPLATE.formatted(uniqueName), "sa", "");
        Flyway.configure()
                .dataSource(dataSource)
                .locations(FLYWAY_LOCATION)
                .load()
                .migrate();
        return new JdbcTemplate(dataSource);
    }
}
