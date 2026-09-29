package io.github.oatelauser.jauth.selfservice.support;

import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.Driver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * 集成测试支撑（core 同名支撑的模块内副本：core 的测试源不经 test-jar 导出，多模块无共享测试基建的既定形态）。
 * H2（PostgreSQL 兼容模式）内存库 + core 命名空间 Flyway 迁移，一步产出 {@link JdbcTemplate}。
 *
 * @author oatelauser
 */
public final class IntegrationTestSupport {

    /** 迁移脚本位置：core 命名空间（表结构唯一真源，测试不另建 DDL）。 */
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
