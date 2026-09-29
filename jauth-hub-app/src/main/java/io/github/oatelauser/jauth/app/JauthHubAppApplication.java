package io.github.oatelauser.jauth.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * jauth-hub 独立部署壳（SPEC §2）：自持用户库（jdbc 存储 + 自备 {@code UserDetailsService}）、首启超管
 * seeding、/demo 教学区、spring-plus 三件套全必选。引入 starter 即接管式装配生效，本类只补壳层自有组件。
 *
 * @author oatelauser
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class JauthHubAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(JauthHubAppApplication.class, args);
    }
}
