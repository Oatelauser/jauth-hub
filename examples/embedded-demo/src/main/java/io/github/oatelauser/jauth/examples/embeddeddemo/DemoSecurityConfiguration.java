package io.github.oatelauser.jauth.examples.embeddeddemo;

import io.github.oatelauser.jauth.resourceserver.JauthResourceServerConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 宿主侧安全装配（嵌入契约的两条硬规则）：自备 {@link UserDetailsService}（示例 1 个内存用户）+ 自配
 * default 链（denyAll + 显式白名单）。
 *
 * <p>密码编码器必须替换 starter 默认的裸 BCrypt 为 {@code DelegatingPasswordEncoder}：框架端
 * {@code ClientSecretAuthenticationProvider} 固定按 {@code {bcrypt}} 前缀格式校验 client_secret，裸 bcrypt
 * 哈希（无前缀）会在 HTTP 凭证校验时直接抛 "no PasswordEncoder mapped for the id null"——README 演练的
 * 内省一步即依赖此接线。示例用户密码为占位值，勿用于任何真实环境。
 *
 * @author oatelauser
 */
@Configuration(proxyBeanMethods = false)
public class DemoSecurityConfiguration {

    /** 示例账号（占位密码，README 演练用）：user / demo-user-password-placeholder。 */
    static final String DEMO_USERNAME = "user";

    static final String DEMO_PASSWORD = "demo-user-password-placeholder";

    @Bean
    PasswordEncoder demoPasswordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService demoUserDetailsService(PasswordEncoder passwordEncoder) {
        UserDetails demoUser = User.withUsername(DEMO_USERNAME)
                .password(passwordEncoder.encode(DEMO_PASSWORD))
                .roles("USER")
                .build();
        return new InMemoryUserDetailsManager(demoUser);
    }

    /**
     * 宿主 default 链：/public/** 与 /front/** 放行、/api/** 经内省验 opaque token、其余 denyAll。
     * jauth 协议链（starter 装配，序位 100）认领协议端点与 /login，与本链互不越界。
     *
     * <p>/front/** 是 v1.5 B5a 的"白得 UI"示范：宿主引 {@code jauth-hub-front-dist} 依赖后，Boot
     * 默认静态映射把皮从 classpath 出网——但静态资源不豁免安全链，denyAll 宿主必须显式放行该前缀，
     * 否则皮在 classpath 也被 401 拦在门前。宿主侧已知边界：嵌入宿主没有 jauth-hub-app 的
     * AppWebConfiguration，history 深链（如 /front/login）不回退 index.html，须宿主自配
     * {@code PathResourceResolver}（参考 AppWebConfiguration）。
     */
    @Bean
    SecurityFilterChain demoDefaultSecurityFilterChain(HttpSecurity http, JauthResourceServerConfigurer resourceServer)
            throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/public/**", "/front/**", "/error")
                        .permitAll()
                        .requestMatchers("/api/**")
                        .authenticated()
                        .anyRequest()
                        .denyAll())
                .oauth2ResourceServer(resourceServer);
        return http.build();
    }
}
