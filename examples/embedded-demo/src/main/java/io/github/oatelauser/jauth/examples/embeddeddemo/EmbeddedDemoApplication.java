package io.github.oatelauser.jauth.examples.embeddeddemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

/**
 * 内嵌接入示例宿主（SPEC §2 examples/）：一个不依赖 jauth-hub-app 的独立 Boot 应用，引 jauth-hub-starter
 * 即得完整认证中心（memory 存储，零数据库依赖），自家业务接口经 rs-starter 内省自保护——06 Q1 裁定的
 * "宿主接口自己接 OAuth" 模式的最小样板。接入步骤见本模块 README.md。
 *
 * <p>exclude DataSourceAutoConfiguration：memory 模式零数据库，而 classpath 上的 HikariCP（core 传递的
 * starter-jdbc）会让 Boot 空配建池失败。正式项目切 {@code jauth-hub.storage: jdbc} 时去掉本 exclude 并
 * 提供 DataSource 即可（Flyway 私有历史表自动迁移）。
 *
 * @author oatelauser
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class EmbeddedDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(EmbeddedDemoApplication.class, args);
    }
}
