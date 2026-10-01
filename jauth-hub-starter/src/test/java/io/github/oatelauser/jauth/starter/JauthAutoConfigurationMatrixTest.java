package io.github.oatelauser.jauth.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.source.JWKSource;
import io.github.oatelauser.jauth.core.authorization.AuditingOAuth2AuthorizationService;
import io.github.oatelauser.jauth.core.client.JauthJdbcRegisteredClientRepository;
import io.github.oatelauser.jauth.core.response.DefaultResponseRenderer;
import io.github.oatelauser.jauth.core.response.ResponseRenderer;
import io.github.oatelauser.jauth.core.token.JdbcTokenFamilyService;
import io.github.oatelauser.jauth.core.user.JdbcUserRepository;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import io.github.oatelauser.springplus.web.response.SimpleResponse;
import java.util.List;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * 装配矩阵（ApplicationContextRunner 面）：模式 × bean 在场性、ConditionalOnMissingBean 让位、
 * spring-plus 桥开关、CORS 开关、链序位可配——快速条件回归，不起完整宿主。
 *
 * @author oatelauser
 */
class JauthAutoConfigurationMatrixTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JauthSpringPlusBridgeAutoConfiguration.class, JauthHubAutoConfiguration.class))
            .withUserConfiguration(WebSecurityBase.class)
            .withPropertyValues(
                    "jauth-hub.clients[0].client-id=matrix-client",
                    "jauth-hub.clients[0].client-name=Matrix Client",
                    "jauth-hub.clients[0].client-secret=matrix-secret",
                    "jauth-hub.clients[0].grant-types[0]=authorization_code",
                    "jauth-hub.clients[0].redirect-uris[0]=https://example.com/cb",
                    "jauth-hub.clients[0].scopes[0]=openid");

    @Test
    @DisplayName("默认（memory）：内存三件在场、JDBC 件缺席、种子已构造成初始注册")
    void memoryModeMatrix() {
        runner.run(context -> {
            assertThat(context).hasBean("jauthRegisteredClientRepository");
            assertThat(context.getBean("jauthRegisteredClientRepository"))
                    .isInstanceOf(
                            org.springframework.security.oauth2.server.authorization.client
                                    .InMemoryRegisteredClientRepository.class);
            assertThat(context.getBean(RegisteredClientRepository.class).findByClientId("matrix-client"))
                    .isNotNull();
            assertThat(context).hasBean("jauthAuthorizationService");
            // B7：族谱包装版被审计装饰包裹（生命周期事件），断言到装饰层
            assertThat(context.getBean("jauthAuthorizationService"))
                    .isInstanceOf(AuditingOAuth2AuthorizationService.class);
            assertThat(context).doesNotHaveBean(JauthJdbcRegisteredClientRepository.class);
            assertThat(context).doesNotHaveBean(JdbcTokenFamilyService.class);
            assertThat(context).doesNotHaveBean(JdbcUserRepository.class);
            assertThat(context).hasSingleBean(JWKSource.class);
        });
    }

    @Test
    @DisplayName("memory 空种子 fail-fast：框架 InMemory 容器不可空构造（不静默注入占位 client）")
    void memoryModeWithoutSeedsFailsFast() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        JauthSpringPlusBridgeAutoConfiguration.class, JauthHubAutoConfiguration.class))
                .withUserConfiguration(WebSecurityBase.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("requires at least one jauth-hub.clients[] seed");
                });
    }

    @Test
    @DisplayName("jdbc 模式：JDBC 件在场、内存件缺席、私有 Flyway 表建到 v7")
    void jdbcModeMatrix() {
        DataSource dataSource = testDataSource("matrix-jdbc");
        runner.withPropertyValues("jauth-hub.storage=jdbc")
                .withBean(DataSource.class, () -> dataSource)
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource))
                .run(context -> {
                    assertThat(context).hasSingleBean(JauthJdbcRegisteredClientRepository.class);
                    assertThat(context).hasBean("jauthAuthorizationService");
                    assertThat(context).hasSingleBean(JdbcTokenFamilyService.class);
                    assertThat(context).hasSingleBean(JdbcUserRepository.class);
                    assertThat(context.getBean("jauthAuthorizationService"))
                            .isInstanceOf(AuditingOAuth2AuthorizationService.class);
                    assertThat(context).hasBean("jauthSeedingTransactionTemplate");
                    String maxVersion = new JdbcTemplate(context.getBean(DataSource.class))
                            .queryForObject("SELECT MAX(version) FROM jauth_flyway_schema_history", String.class);
                    assertThat(maxVersion).isEqualTo("8");
                });
    }

    @Test
    @DisplayName("jdbc 无 DataSource：fail-fast 启动失败（嵌入契约，不静默回退）")
    void jdbcWithoutDataSourceFailsFast() {
        runner.withPropertyValues("jauth-hub.storage=jdbc").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("DataSource");
        });
    }

    @Test
    @DisplayName("宿主自定 renderer：默认与桥双双让位（ConditionalOnMissingBean 决议 4）")
    void hostRendererTakesPrecedence() {
        runner.withBean("hostRenderer", ResponseRenderer.class, () -> new ResponseRenderer() {
                    @Override
                    public Object renderSuccess(Object data) {
                        return data;
                    }

                    @Override
                    public Object renderFail(String code, String message) {
                        return message;
                    }
                })
                .run(context -> {
                    assertThat(context.getBean(ResponseRenderer.class)).isSameAs(context.getBean("hostRenderer"));
                    assertThat(context).doesNotHaveBean(DefaultResponseRenderer.class);
                    assertThat(context)
                            .doesNotHaveBean(JauthSpringPlusBridgeAutoConfiguration.SimpleResponseRenderer.class);
                });
    }

    @Test
    @DisplayName("桥开关：SimpleResponse 不在 classpath（FilteredClassLoader）→ 桥不生效，回落默认渲染器")
    void bridgeBacksOffWithoutSimpleResponse() {
        runner.withClassLoader(new org.springframework.boot.test.context.FilteredClassLoader(SimpleResponse.class))
                .run(context -> {
                    assertThat(context)
                            .doesNotHaveBean(JauthSpringPlusBridgeAutoConfiguration.SimpleResponseRenderer.class);
                    assertThat(context.getBean(ResponseRenderer.class)).isInstanceOf(DefaultResponseRenderer.class);
                });
    }

    @Test
    @DisplayName("桥在场时输出 SimpleResponse 类型（renderSuccess 返回真 SimpleResponse）")
    void bridgeRendersSimpleResponse() {
        runner.run(context -> {
            ResponseRenderer renderer = context.getBean(ResponseRenderer.class);
            Object rendered = renderer.renderSuccess(List.of("a"));
            assertThat(rendered).isInstanceOf(SimpleResponse.class);
            assertThat(((SimpleResponse<?>) rendered).getCode()).isEqualTo("00000");
        });
    }

    @Test
    @DisplayName("教学开关绑定：educational=false 关教学层")
    void educationalFlagBinds() {
        runner.withPropertyValues("jauth-hub.educational=false").run(context -> assertThat(
                        context.getBean(EducationalFlag.class).enabled())
                .isFalse());
        runner.run(context ->
                assertThat(context.getBean(EducationalFlag.class).enabled()).isTrue());
    }

    @Test
    @DisplayName("CORS 开关：配来源→协议端点有 CORS 配置且链上有 CorsFilter；默认无")
    void corsToggle() {
        runner.withPropertyValues("jauth-hub.cors.allowed-origins=https://spa.example.com")
                .run(context -> {
                    CorsConfigurationSource source = context.getBean(CorsConfigurationSource.class);
                    CorsConfiguration configuration = source.getCorsConfiguration(request("POST", "/oauth2/token"));
                    assertThat(configuration).isNotNull();
                    assertThat(configuration.getAllowedOrigins()).containsExactly("https://spa.example.com");
                    assertThat(corsFilterCount(context.getBean(SecurityFilterChain.class)))
                            .isEqualTo(1);
                });
        runner.run(context -> {
            CorsConfigurationSource source = context.getBean(CorsConfigurationSource.class);
            assertThat(source.getCorsConfiguration(request("POST", "/oauth2/token")))
                    .isNull();
            assertThat(corsFilterCount(context.getBean(SecurityFilterChain.class)))
                    .isZero();
        });
    }

    @Test
    @DisplayName("链序位可配：filter-chain-order=42 进链排序（Ordered 委托）")
    void filterChainOrderIsConfigurable() {
        runner.withPropertyValues("jauth-hub.filter-chain-order=42").run(context -> {
            SecurityFilterChain chain = context.getBean(SecurityFilterChain.class);
            assertThat(chain).isInstanceOf(Ordered.class);
            assertThat(((Ordered) chain).getOrder()).isEqualTo(42);
        });
    }

    private static long corsFilterCount(SecurityFilterChain chain) {
        return chain.getFilters().stream()
                .filter(filter -> filter instanceof org.springframework.web.filter.CorsFilter)
                .count();
    }

    private static MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    private static DataSource testDataSource(String name) {
        JdbcDataSource dataSource = new JdbcDataSource();
        // DATABASE_TO_LOWER=TRUE 等 PG 折叠参数与 core 测试基准一致（IntegrationTestSupport）
        dataSource.setUrl("jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;"
                + "DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    @Test
    @DisplayName("sudo 依赖 passkey（SPEC §5）：passkey 关而 sudo 开启动失败；双开时 SudoGate 在场")
    void sudoRequiresPasskeyFailFast() {
        this.runner.withPropertyValues("jauth-hub.sudo.enabled=true").run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .hasMessageContaining("requires jauth-hub.passkey.enabled=true"));
        this.runner
                .withPropertyValues(
                        "jauth-hub.passkey.enabled=true",
                        "jauth-hub.sudo.enabled=true",
                        "jauth-hub.sudo.ttl-minutes=30")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("jauthSudoGate");
                });
    }

    /**
     * HttpSecurity 基座：spring-security 的 HttpSecurity/WebSecurity 装配不在 Boot 自动配置清单内，
     * 由 @EnableWebSecurity 拉起（@SpringBootTest 场景里 Boot 的安全自动配置承担同一角色）。
     */
    @Configuration(proxyBeanMethods = false)
    @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
    static class WebSecurityBase {

        @Bean
        UserDetailsService userDetailsService() {
            return new InMemoryUserDetailsManager(User.withUsername("user")
                    .password("{noop}password")
                    .roles("USER")
                    .build());
        }
    }
}
