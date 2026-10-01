package io.github.oatelauser.jauth.starter;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oatelauser.jauth.core.passkey.JdbcPasskeyCredentialRepository;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.test.context.TestPropertySource;

/**
 * passkey 开启 + jdbc 存储集成测试（SPEC §5 v1.2）：V8 迁移随私有 Flyway 历史在 H2 落地、JDBC 凭据仓储在场。
 *
 * @author oatelauser
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        classes = {JauthHubStarterTestApplication.class, PasskeyJdbcIntegrationTest.JdbcPasskeyHostConfiguration.class})
@TestPropertySource(
        properties = {
            "spring.autoconfigure.exclude=io.github.oatelauser.springplus.web.autoconfigure.ExceptionHandlingAutoConfiguration,"
                    + "io.github.oatelauser.springplus.web.autoconfigure.SpringPlusWebAutoConfiguration",
            "spring.datasource.url=jdbc:h2:mem:jauth-passkey-jdbc-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "jauth-hub.storage=jdbc",
            "jauth-hub.passkey.enabled=true",
            "jauth-hub.clients[0].client-id=jdbc-client",
            "jauth-hub.clients[0].client-name=JDBC Client",
            "jauth-hub.clients[0].client-secret=jdbc-secret",
            "jauth-hub.clients[0].grant-types[0]=authorization_code",
            "jauth-hub.clients[0].grant-types[1]=refresh_token",
            "jauth-hub.clients[0].redirect-uris[0]=https://example.com/cb",
            "jauth-hub.clients[0].scopes[0]=openid"
        })
class PasskeyJdbcIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("JDBC 凭据仓储在场 + V8 补列已在私有 Flyway 历史中落地")
    void jdbcPasskeyRepositoryAndV8Migration() {
        assertThat(this.applicationContext.getBean(UserCredentialRepository.class))
                .isInstanceOf(JdbcPasskeyCredentialRepository.class);
        assertThat(this.applicationContext
                        .getBean(PublicKeyCredentialUserEntityRepository.class)
                        .getClass()
                        .getSimpleName())
                .isEqualTo("JauthUserEntityRepository");

        Integer columnCount = this.jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'jauth_user_credential'"
                        + " AND column_name IN ('label', 'credential_type', 'backup_eligible', 'backup_state',"
                        + " 'uv_initialized', 'transports', 'attestation_object', 'attestation_client_data_json')",
                Integer.class);
        assertThat(columnCount).isEqualTo(8);

        // 端到端落行：经仓储 save（框架注册路径的挂点）写一行并回读
        this.userRepository.save(new JauthUser(
                "018f0000-0000-7000-8000-0000000000aa",
                "alice",
                "{bcrypt}placeholder-not-a-real-hash",
                null,
                null,
                JauthUser.ROLE_USER,
                JauthUser.STATUS_ACTIVE,
                null,
                Instant.parse("2026-10-01T07:00:00Z")));
        PublicKeyCredentialUserEntityRepository userEntities =
                this.applicationContext.getBean(PublicKeyCredentialUserEntityRepository.class);
        assertThat(userEntities.findByUsername("alice")).isNotNull();
    }

    /** 与 memory 版同款：WebAuthn 构链要求的 UserDetailsService。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class JdbcPasskeyHostConfiguration {

        @Bean
        UserDetailsService passkeyUserDetailsService(UserRepository userRepository) {
            return username -> {
                JauthUser user = userRepository.findByUsername(username);
                return User.withUsername(username)
                        .password(user.passwordHash())
                        .roles(user.role())
                        .build();
            };
        }
    }
}
