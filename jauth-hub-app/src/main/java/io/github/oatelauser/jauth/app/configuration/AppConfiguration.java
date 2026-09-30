package io.github.oatelauser.jauth.app.configuration;

import io.github.oatelauser.jauth.app.bootstrap.SuperAdminProperties;
import io.github.oatelauser.jauth.app.bootstrap.SuperAdminSeeder;
import io.github.oatelauser.jauth.app.user.AppUserDetailsService;
import io.github.oatelauser.jauth.core.user.UserRepository;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 壳层用户域装配：自备 {@link AppUserDetailsService}（嵌入契约的 app 侧兑现）+ Delegating 密码编码器 +
 * 首启超管 seeding。
 *
 * <p><b>密码编码器为什么是 Delegating 而非裸 bcrypt</b>：框架端 {@code ClientSecretAuthenticationProvider}
 * 固定用 {@code PasswordEncoderFactories.createDelegatingPasswordEncoder()} 校验 client_secret——存储哈希必须
 * 带 {@code {bcrypt}} 前缀，否则 {@code matches} 直接抛 "no PasswordEncoder mapped for the id null"。starter
 * 默认注册裸 BCrypt（其种子走 bean 内编码、无框架端 HTTP 校验场景），本壳的客户端经 /introspect 等端点做
 * HTTP 凭证校验，故整体替换为 Delegating（宿主优先，starter 的 BCrypt 让位）。
 *
 * @author oatelauser
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SuperAdminProperties.class)
public class AppConfiguration {

    @Bean
    PasswordEncoder appPasswordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    AppUserDetailsService appUserDetailsService(UserRepository userRepository) {
        return new AppUserDetailsService(userRepository);
    }

    /** 首启播种 runner：晚于 client 播种与否无依赖，幂等可重入。 */
    @Bean
    ApplicationRunner superAdminSeederRunner(
            SuperAdminProperties properties, UserRepository userRepository, PasswordEncoder passwordEncoder) {
        return args -> new SuperAdminSeeder(properties, userRepository, passwordEncoder).seed();
    }
}
