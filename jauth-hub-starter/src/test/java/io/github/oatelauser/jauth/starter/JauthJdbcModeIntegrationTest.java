package io.github.oatelauser.jauth.starter;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oatelauser.jauth.core.authorization.AuditingOAuth2AuthorizationConsentService;
import io.github.oatelauser.jauth.core.authorization.AuditingOAuth2AuthorizationService;
import io.github.oatelauser.jauth.core.client.ClientSeedProperties;
import io.github.oatelauser.jauth.core.client.ClientSeeder;
import io.github.oatelauser.jauth.core.client.JauthJdbcRegisteredClientRepository;
import io.github.oatelauser.jauth.core.token.JdbcTokenFamilyService;
import io.github.oatelauser.jauth.core.token.key.JwkRotationService;
import io.github.oatelauser.jauth.core.user.JdbcUserRepository;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.context.TestPropertySource;

/**
 * jdbc 模式集成测试（H2 PostgreSQL 兼容模式，SPEC §1 决议 8 的测试基准）：JDBC 三件 + 私有 Flyway 历史
 * 表迁移到 v5 + 播种幂等 + 轮转调度随 context 启停。Boot 宿主自己的 Flyway（默认表）与本侧私有表并行
 * 不冲突——同为断言对象。
 *
 * @author oatelauser
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, classes = JauthHubStarterTestApplication.class)
@TestPropertySource(
        properties = {
            "spring.autoconfigure.exclude=io.github.oatelauser.springplus.web.autoconfigure.ExceptionHandlingAutoConfiguration,"
                    + "io.github.oatelauser.springplus.web.autoconfigure.SpringPlusWebAutoConfiguration",
            "spring.datasource.url=jdbc:h2:mem:jauth-jdbc-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "jauth-hub.storage=jdbc",
            "jauth-hub.clients[0].client-id=jdbc-client",
            "jauth-hub.clients[0].client-name=JDBC Client",
            "jauth-hub.clients[0].client-secret=jdbc-secret",
            "jauth-hub.clients[0].grant-types[0]=authorization_code",
            "jauth-hub.clients[0].grant-types[1]=refresh_token",
            "jauth-hub.clients[0].redirect-uris[0]=https://example.com/cb",
            "jauth-hub.clients[0].scopes[0]=openid"
        })
class JauthJdbcModeIntegrationTest {

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Autowired
    private OAuth2AuthorizationService authorizationService;

    @Autowired
    private OAuth2AuthorizationConsentService authorizationConsentService;

    @Autowired
    private JdbcUserRepository userRepository;

    @Autowired
    private JdbcTokenFamilyService tokenFamilyService;

    @Autowired
    private JwkRotationService jwkRotationService;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("JDBC 三件在场：JauthJdbc client/authorization + 框架 JdbcOAuth2AuthorizationConsentService")
    void jdbcStorageBeans() {
        assertThat(registeredClientRepository).isInstanceOf(JauthJdbcRegisteredClientRepository.class);
        // B7：JDBC 哈希手术版被 PAT 叠加 + 审计装饰包裹，断言到装饰层
        assertThat(authorizationService).isInstanceOf(AuditingOAuth2AuthorizationService.class);
        assertThat(authorizationConsentService).isInstanceOf(AuditingOAuth2AuthorizationConsentService.class);
        assertThat(tokenFamilyService).isInstanceOf(JdbcTokenFamilyService.class);
        assertThat(userRepository).isInstanceOf(JdbcUserRepository.class);
    }

    @Test
    @DisplayName("私有 Flyway 表迁移到 v6，与宿主默认表并存不撞（隔离决议的实证）")
    void flywayPrivateHistoryTable() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        String maxVersion =
                jdbcTemplate.queryForObject("SELECT MAX(version) FROM jauth_flyway_schema_history", String.class);
        assertThat(maxVersion).isEqualTo("6");
        Integer clientRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM oauth2_registered_client WHERE client_id = 'jdbc-client'", Integer.class);
        assertThat(clientRows).isEqualTo(1);
    }

    @Test
    @DisplayName("播种幂等：同配置重跑播种不增行（properties 是播种器不是第二真源）")
    void seedingIdempotent() {
        ClientSeedProperties seedProperties = new ClientSeedProperties();
        ClientSeedProperties.ClientSeed seed = new ClientSeedProperties.ClientSeed();
        seed.setClientId("jdbc-client");
        seed.setClientName("JDBC Client");
        seed.setClientSecret("jdbc-secret");
        seed.setGrantTypes(List.of("authorization_code", "refresh_token"));
        seed.setRedirectUris(List.of("https://example.com/cb"));
        seed.setScopes(List.of("openid"));
        seedProperties.setClients(List.of(seed));

        new ClientSeeder(seedProperties, passwordEncoder).seed(registeredClientRepository);

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        Integer clientRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM oauth2_registered_client WHERE client_id = 'jdbc-client'", Integer.class);
        assertThat(clientRows).isEqualTo(1);
    }

    @Test
    @DisplayName("轮转调度生命周期：context 启动即 running，手动 stop 即停")
    void rotationLifecycleFollowsContext() {
        JauthHubAutoConfiguration.JwkRotationLifecycle lifecycle =
                new JauthHubAutoConfiguration.JwkRotationLifecycle(jwkRotationService);
        assertThat(lifecycle.isRunning()).isFalse();
        lifecycle.start();
        assertThat(lifecycle.isRunning()).isTrue();
        lifecycle.stop();
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    @DisplayName("真实 context 启停：SpringApplication 起来即 running，close 后即停（SmartLifecycle 挂钩）")
    void rotationLifecycleWithRealContext() {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(JauthHubStarterTestApplication.class)
                .properties(
                        "spring.autoconfigure.exclude=io.github.oatelauser.springplus.web.autoconfigure.ExceptionHandlingAutoConfiguration,"
                                + "io.github.oatelauser.springplus.web.autoconfigure.SpringPlusWebAutoConfiguration",
                        "spring.datasource.url=jdbc:h2:mem:jauth-jdbc-lifecycle;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "jauth-hub.storage=jdbc",
                        "jauth-hub.clients[0].client-id=lifecycle-client",
                        "jauth-hub.clients[0].client-name=Lifecycle Client",
                        "jauth-hub.clients[0].grant-types[0]=authorization_code",
                        "jauth-hub.clients[0].redirect-uris[0]=https://example.com/cb",
                        "jauth-hub.clients[0].scopes[0]=openid")
                .run();
        JauthHubAutoConfiguration.JwkRotationLifecycle lifecycle =
                context.getBean(JauthHubAutoConfiguration.JwkRotationLifecycle.class);
        try {
            assertThat(lifecycle.isRunning()).as("context 启动后轮转调度在跑").isTrue();
        } finally {
            context.close();
        }
        assertThat(lifecycle.isRunning()).as("context 关闭后轮转调度停了").isFalse();
    }
}
