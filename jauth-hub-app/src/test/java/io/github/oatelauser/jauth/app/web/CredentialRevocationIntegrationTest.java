package io.github.oatelauser.jauth.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.oatelauser.jauth.app.JauthHubAppApplication;
import io.github.oatelauser.jauth.core.client.JauthJdbcRegisteredClientRepository;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.util.UuidV7;
import io.github.oatelauser.jauth.selfservice.pat.PatService;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 账号状态变更清剿端到端（v1.3 D1，fail-secure 口径）：管理员重置密码 → 目标用户授权行全删、族谱整主体
 * BURNED、会话全失效（他人会话不动）、PAT 保留；停用 → 在前述之上 PAT 全撤销；自助改密 → 当前会话保留、
 * 其余全失效。授权行经真实授权服务链落库（哈希列、族谱全真），会话行直插 spring_session（清剿消费的是
 * principal 索引列，与会话内容无关）。审计 credentials.revoked 落库断言顺带覆盖。
 *
 * <p>sudo 保持默认关（@RequiresSudo 直通）——门禁语义另由 SudoGatingIntegrationTest 覆盖，两关注分离。
 *
 * @author oatelauser
 */
@SpringBootTest(classes = JauthHubAppApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:jauth-credential-revocation-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "jauth-hub.bootstrap.superadmin.username=superadmin",
            "jauth-hub.bootstrap.superadmin.password=super-secret-placeholder"
        })
class CredentialRevocationIntegrationTest {

    private static final String ADMIN_USERNAME = "superadmin";

    private static final Instant ISSUED_AT = Instant.parse("2026-10-02T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private OAuth2AuthorizationService authorizationService;

    @Autowired
    private JauthJdbcRegisteredClientRepository registeredClientRepository;

    @Autowired
    private PatService patService;

    @Test
    @DisplayName("管理员重置：授权全删+族谱 BURNED+目标会话全失效；他人行不动；PAT 保留")
    void adminResetRevokesAuthorizationsAndSessionsButKeepsPats() throws Exception {
        String username = "cred-victim-reset";
        String userId = seedUser(username);
        seedAuthorization(username, "auth-reset-1");
        insertSession("sess-reset-1", username);
        insertSession("sess-reset-2", username);
        insertSession("sess-admin", ADMIN_USERNAME);
        patService.create(userId, "CI 部署", Set.of("openid"), Duration.ofDays(90));

        this.mockMvc
                .perform(post("/api/admin/users/{id}/password", userId)
                        .with(user(ADMIN_USERNAME).roles("SUPER_ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"reset-pass-placeholder\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));

        assertThat(authorizationRows(username)).isZero();
        assertThat(familyStatuses(username)).containsExactly("BURNED");
        assertThat(sessionsOf(username)).isZero();
        assertThat(sessionsOf(ADMIN_USERNAME)).isEqualTo(1);
        assertThat(patService.listActive(userId)).as("重置不清 PAT").hasSize(1);
        assertThat(revocationEvents("password_changed")).isPositive();
    }

    @Test
    @DisplayName("停用：授权与会话全失效之外，PAT 一并撤销（账号死则凭据全死）")
    void disableAlsoRevokesPats() throws Exception {
        String username = "cred-victim-disable";
        String userId = seedUser(username);
        seedAuthorization(username, "auth-disable-1");
        insertSession("sess-disable-1", username);
        patService.create(userId, "批量撤销甲", Set.of("openid"), Duration.ofDays(90));
        patService.create(userId, "批量撤销乙", Set.of("openid"), Duration.ofDays(90));

        this.mockMvc
                .perform(post("/api/admin/users/{id}/status", userId)
                        .with(user(ADMIN_USERNAME).roles("SUPER_ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));

        assertThat(authorizationRows(username)).isZero();
        assertThat(familyStatuses(username)).containsExactly("BURNED");
        assertThat(sessionsOf(username)).isZero();
        assertThat(patService.listActive(userId)).as("停用连 PAT 全撤").isEmpty();
        assertThat(revocationEvents("user_disabled")).isPositive();
        assertThat(this.userRepository.findById(userId).status()).isEqualTo(JauthUser.STATUS_DISABLED);
    }

    @Test
    @DisplayName("自助改密：当前会话保留，其余会话与全部授权失效")
    void selfChangePasswordKeepsCurrentSessionOnly() throws Exception {
        String username = "cred-victim-self";
        String userId = seedUser(username);
        seedAuthorization(username, "auth-self-1");
        MockHttpSession currentSession = new MockHttpSession(null, "sess-self-current");
        insertSession("sess-self-current", username);
        insertSession("sess-self-other", username);

        this.mockMvc
                .perform(
                        post("/api/profile/password")
                                .with(user(username))
                                .with(csrf())
                                .session(currentSession)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"oldPassword\":\"victim-pass-placeholder\",\"newPassword\":\"victim-new-pass-placeholder\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));

        assertThat(authorizationRows(username)).isZero();
        assertThat(familyStatuses(username)).containsExactly("BURNED");
        assertThat(sessionsOf(username)).as("当前会话保留").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM spring_session WHERE primary_id = ?", Integer.class, "sess-self-current"))
                .isEqualTo(1);
        assertThat(userId).isNotNull();
    }

    /** 域面直建目标用户（每方法独立用户名，免执行顺序耦合）。 */
    private String seedUser(String username) {
        JauthUser user = new JauthUser(
                UuidV7.generate().toString(),
                username,
                this.passwordEncoder.encode("victim-pass-placeholder"),
                null,
                null,
                JauthUser.ROLE_USER,
                JauthUser.STATUS_ACTIVE,
                null,
                Instant.now());
        this.userRepository.save(user);
        return user.id();
    }

    /** 经真实授权服务链落一条带 refresh 的授权（族谱随 save 全真记录）。 */
    private void seedAuthorization(String principalName, String authorizationId) {
        String clientId = "cred-revoker-client-" + authorizationId;
        RegisteredClient client = RegisteredClient.withId(UuidV7.generate().toString())
                .clientId(clientId)
                .clientName(clientId)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("https://placeholder.example.com/callback")
                .scope("openid")
                .build();
        this.registeredClientRepository.save(client);
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "placeholder-access-" + authorizationId + "-not-real",
                ISSUED_AT,
                ISSUED_AT.plusSeconds(7200),
                Set.of("openid"));
        OAuth2RefreshToken refreshToken = new OAuth2RefreshToken(
                "placeholder-refresh-" + authorizationId + "-not-real", ISSUED_AT, ISSUED_AT.plusSeconds(2592000));
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .id(authorizationId)
                .principalName(principalName)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of("openid"))
                .token(accessToken)
                .refreshToken(refreshToken)
                .build();
        this.authorizationService.save(authorization);
    }

    /** 直插最小 spring_session 行：清剿消费的是 principal 索引列，与会话内容无关。 */
    private void insertSession(String primaryId, String principalName) {
        long now = System.currentTimeMillis();
        this.jdbcTemplate.update(
                "INSERT INTO spring_session (primary_id, session_id, creation_time, last_access_time,"
                        + " max_inactive_interval, expiry_time, principal_name) VALUES (?, ?, ?, ?, ?, ?, ?)",
                primaryId,
                primaryId,
                now,
                now,
                1800,
                now + 1_800_000,
                principalName);
    }

    private Integer authorizationRows(String principalName) {
        return this.jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM oauth2_authorization WHERE principal_name = ?", Integer.class, principalName);
    }

    private java.util.List<String> familyStatuses(String principalName) {
        return this.jdbcTemplate.queryForList(
                "SELECT status FROM jauth_token_family WHERE principal_name = ?", String.class, principalName);
    }

    private Integer sessionsOf(String principalName) {
        return this.jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM spring_session WHERE principal_name = ?", Integer.class, principalName);
    }

    private Integer revocationEvents(String reason) {
        return this.jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM jauth_audit_event WHERE event_type = 'credentials.revoked'"
                        + " AND detail LIKE ?",
                Integer.class,
                "%reason=" + reason + "%");
    }
}
