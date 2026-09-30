package io.github.oatelauser.jauth.app.configuration;

import io.github.oatelauser.jauth.resourceserver.JauthResourceServerConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.web.accept.ContentNegotiationStrategy;
import org.springframework.web.accept.HeaderContentNegotiationStrategy;

/**
 * 壳层 default 链（SPEC §2 宿主链共存四规则之四：default 链只由 app 提供）。
 *
 * <p>jauth 协议链（序位 100，starter 装配）已认领协议端点 ∪ /login ∪ /css/**；本链兜其余一切：/demo 教学区
 * 公开、/selfservice 与 /api 需认证，<b>anyRequest denyAll</b>（spring-plus 安全红线：默认关大门，放行走显式
 * 白名单）。/api/demo/** 经 rs-starter 内省验 opaque token；/api/admin/** 的角色判定交给 spring-plus
 * {@code @RequiresRole} 方法级拦截（08 票：GrantedAuthority 路线零代码）。
 *
 * <p>认证失败入口：非 HTML 请求由资源服务器装配注册 Bearer 入口（401 + WWW-Authenticate），浏览器请求回落
 * 本类配置的 /login 重定向（登录页归协议链，POST /login 不到本链）。
 *
 * @author oatelauser
 */
@Configuration(proxyBeanMethods = false)
public class AppSecurityConfiguration {

    /**
     * 与协议链同名的登录页路径（starter 常量值；跨模块不可引，按 SPEC 锁定路径复制）。
     */
    private static final String LOGIN_PATH = "/login";

    @Bean
    SecurityFilterChain appDefaultSecurityFilterChain(HttpSecurity http, JauthResourceServerConfigurer resourceServer) {
        // 入口分派：API 未认证一律 401（无 Accept 头的客户端也要确定性语义），text/html 浏览器请求 302 /login。
        // 注意不可用 authenticationEntryPoint(...) 平铺设置——它会整体压制 defaultAuthenticationEntryPointFor
        // 的分派表（ExceptionHandlingConfigurer 求值顺序：显式 setter 优先于一切映射）。
        ContentNegotiationStrategy contentNegotiationStrategy = http.getSharedObject(ContentNegotiationStrategy.class);
        if (contentNegotiationStrategy == null) {
            contentNegotiationStrategy = new HeaderContentNegotiationStrategy();
        }
        MediaTypeRequestMatcher textHtmlMatcher = new MediaTypeRequestMatcher(
                contentNegotiationStrategy, MediaType.APPLICATION_XHTML_XML, MediaType.TEXT_HTML);
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/demo/**", "/error")
                        .permitAll()
                        // actuator（SPEC §8）：health/info 公开（探活/元信息），metrics 需认证
                        .requestMatchers("/actuator/health", "/actuator/info")
                        .permitAll()
                        .requestMatchers("/actuator/**")
                        .authenticated()
                        .requestMatchers("/selfservice/**", "/api/**")
                        .authenticated()
                        .anyRequest()
                        .denyAll())
                .oauth2ResourceServer(resourceServer)
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                PathPatternRequestMatcher.withDefaults().matcher("/api/**"))
                        .defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint(LOGIN_PATH), textHtmlMatcher));
        return http.build();
    }
}
