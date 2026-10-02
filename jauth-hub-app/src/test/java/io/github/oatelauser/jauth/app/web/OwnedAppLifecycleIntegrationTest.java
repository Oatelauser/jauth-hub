package io.github.oatelauser.jauth.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.oatelauser.jauth.app.JauthHubAppApplication;
import io.github.oatelauser.jauth.core.client.JauthJdbcRegisteredClientRepository;
import io.github.oatelauser.jauth.core.org.InstallationService;
import io.github.oatelauser.jauth.core.org.Org;
import io.github.oatelauser.jauth.core.org.OrgService;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.util.UuidV7;
import io.github.oatelauser.jauth.selfservice.web.OwnedAppService;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 应用管理生命周期端到端（v1.3 D2）：轮转（旧 secret 即刻失效、授权不焚）+ 删除级联（授权/consent/
 * 安装随删、族谱按 client 跨主体烧断、审计 client.deleted）+ 编辑落库 + 所有权收紧（他人 B0502）。
 * 授权行经真实授权服务链落库（族谱全真）；consent 行直插（框架三列最小形）。sudo 保持默认关——
 * @RequiresSudo 直通，门禁语义归 SudoGating/SensitiveScope 各自的测试面。
 *
 * @author oatelauser
 */
@SpringBootTest(classes = JauthHubAppApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:jauth-owned-app-lifecycle-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "jauth-hub.bootstrap.superadmin.username=superadmin",
            "jauth-hub.bootstrap.superadmin.password=super-secret-placeholder"
        })
class OwnedAppLifecycleIntegrationTest {

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
    private OwnedAppService ownedAppService;

    @Autowired
    private OrgService orgService;

    @Autowired
    private InstallationService installationService;

    @Autowired
    private JauthJdbcRegisteredClientRepository registeredClientRepository;

    @Test
    @DisplayName("轮转：旧 secret 即刻失效（哈希更换）、授权与族谱原样保留（不焚令牌）")
    void rotateSecretInvalidatesOldSecretButKeepsAuthorizations() throws Exception {
        String username = "lifecycle-rotator";
        String userId = seedUser(username);
        OwnedAppService.Registration registration =
                this.ownedAppService.register(userId, "轮转应用", Set.of("https://rot.example.com/cb"), true);
        String granteeId = seedUser("lifecycle-grantee");
        seedAuthorization("lifecycle-rotator-grant", registration.app().id(), granteeId);
        String originalHash = secretHashOfOriginal(registration);

        this.mockMvc
                .perform(post(
                                "/selfservice/my-apps/{id}/secret",
                                registration.app().id())
                        .with(user(username))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.clientSecret").isNotEmpty());

        String newHash = this.jdbcTemplate.queryForObject(
                "SELECT client_secret FROM oauth2_registered_client WHERE id = ?",
                String.class,
                registration.app().id());
        assertThat(newHash).isNotEqualTo(originalHash);
        assertThat(this.passwordEncoder.matches(registration.plaintextSecret(), newHash))
                .as("旧明文对新哈希不再匹配")
                .isFalse();
        assertThat(authorizationRows(registration.app().id())).as("轮转不焚授权").isEqualTo(1);
        assertThat(familyStatuses(registration.app().id())).containsExactly("ACTIVE");
    }

    @Test
    @DisplayName("删除级联：client 行/授权/consent/安装全清、族谱跨主体 BURNED、client.deleted 审计落库")
    void deleteCascadesEverything() throws Exception {
        String username = "lifecycle-deletor";
        String userId = seedUser(username);
        OwnedAppService.Registration registration =
                this.ownedAppService.register(userId, "待删应用", Set.of("https://del.example.com/cb"), true);
        String appId = registration.app().id();
        String granteeId = seedUser("lifecycle-deletor-grantee");
        seedAuthorization("lifecycle-deletor-grant", appId, granteeId);
        this.jdbcTemplate.update(
                "INSERT INTO oauth2_authorization_consent (registered_client_id, principal_name, authorities) VALUES (?, ?, ?)",
                appId,
                granteeId,
                "openid");
        Org org = this.orgService.create(
                "lifecycle-删除部",
                this.userRepository.findByUsername(ADMIN_USERNAME).id());
        this.installationService.request(appId, org.id(), Set.of("openid"), granteeId);
        assertThat(familyStatuses(appId)).as("删前族谱在册").containsExactly("ACTIVE");

        this.mockMvc
                .perform(delete("/selfservice/my-apps/{id}", appId)
                        .with(user(username))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));

        assertThat(this.jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM oauth2_registered_client WHERE id = ?", Integer.class, appId))
                .isZero();
        assertThat(authorizationRows(appId)).isZero();
        assertThat(this.jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM oauth2_authorization_consent WHERE registered_client_id = ?",
                        Integer.class,
                        appId))
                .isZero();
        assertThat(this.jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM jauth_installation WHERE registered_client_id = ?", Integer.class, appId))
                .isZero();
        // 族谱终态:先被烧断(BURNED),随后 client 行删除经 jauth_token_family_client_fk ON DELETE CASCADE
        // 连带清行——可观察契约 = 该 client 无 ACTIVE 族谱残留(无 FK 部署形态则留 BURNED,同样满足)
        assertThat(familyStatuses(appId)).doesNotContain("ACTIVE");
        assertThat(this.jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM jauth_audit_event WHERE event_type = 'client.deleted'"
                                + " AND target_id = ?",
                        Integer.class,
                        appId))
                .isPositive();
    }

    @Test
    @DisplayName("编辑：改名与回调白名单落库,secret 哈希不动")
    void updateRewritesNameAndRedirectsButNotSecret() throws Exception {
        String username = "lifecycle-editor";
        String userId = seedUser(username);
        OwnedAppService.Registration registration =
                this.ownedAppService.register(userId, "旧名", Set.of("https://old.example.com/cb"), true);

        this.mockMvc
                .perform(post("/selfservice/my-apps/{id}", registration.app().id())
                        .with(user(username))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新名\",\"redirectUris\":\"https://new.example.com/cb\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.name").value("新名"));

        String row = this.jdbcTemplate.queryForObject(
                "SELECT client_name || '|' || redirect_uris FROM oauth2_registered_client WHERE id = ?",
                String.class,
                registration.app().id());
        assertThat(row).isEqualTo("新名|https://new.example.com/cb");
        assertThat(secretHashOfOriginal(registration))
                .isEqualTo(this.jdbcTemplate.queryForObject(
                        "SELECT client_secret FROM oauth2_registered_client WHERE id = ?",
                        String.class,
                        registration.app().id()));
    }

    @Test
    @DisplayName("所有权收紧:他人对本应用轮转/删除一律 B0502 且不落任何级联")
    void foreignOwnerGetsB0502AndNoCascade() throws Exception {
        String username = "lifecycle-owner";
        String userId = seedUser(username);
        OwnedAppService.Registration registration =
                this.ownedAppService.register(userId, "他人勿动", Set.of("https://mine.example.com/cb"), true);
        seedUser("lifecycle-intruder");

        this.mockMvc
                .perform(delete("/selfservice/my-apps/{id}", registration.app().id())
                        .with(user("lifecycle-intruder"))
                        .with(csrf()))
                .andExpect(jsonPath("$.code").value("B0502"));

        assertThat(this.jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM oauth2_registered_client WHERE id = ?",
                        Integer.class,
                        registration.app().id()))
                .isEqualTo(1);
    }

    /** 注册时的哈希留存（对比轮转/编辑前后）。 */
    private String secretHashOfOriginal(OwnedAppService.Registration registration) {
        return this.jdbcTemplate.queryForObject(
                "SELECT client_secret FROM oauth2_registered_client WHERE id = ?",
                String.class,
                registration.app().id());
    }

    /** 域面直建用户(每方法独立用户名,免顺序耦合)。 */
    private String seedUser(String username) {
        JauthUser user = new JauthUser(
                UuidV7.generate().toString(),
                username,
                this.passwordEncoder.encode("lifecycle-pass-placeholder"),
                null,
                null,
                JauthUser.ROLE_USER,
                JauthUser.STATUS_ACTIVE,
                null,
                Instant.now());
        this.userRepository.save(user);
        return user.id();
    }

    /** 经真实授权服务链给 app 落一条带 refresh 的授权(族谱随 save 全真)。 */
    private void seedAuthorization(String authorizationId, String appId, String principalId) {
        String principalName = this.userRepository.findById(principalId).username();
        org.springframework.security.oauth2.server.authorization.client.RegisteredClient client =
                this.registeredClientRepository.findById(appId);
        org.springframework.security.oauth2.core.OAuth2AccessToken accessToken =
                new org.springframework.security.oauth2.core.OAuth2AccessToken(
                        org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType.BEARER,
                        "placeholder-access-" + authorizationId + "-not-real",
                        ISSUED_AT,
                        ISSUED_AT.plusSeconds(7200),
                        Set.of("openid"));
        org.springframework.security.oauth2.core.OAuth2RefreshToken refreshToken =
                new org.springframework.security.oauth2.core.OAuth2RefreshToken(
                        "placeholder-refresh-" + authorizationId + "-not-real",
                        ISSUED_AT,
                        ISSUED_AT.plusSeconds(2592000));
        org.springframework.security.oauth2.server.authorization.OAuth2Authorization authorization =
                org.springframework.security.oauth2.server.authorization.OAuth2Authorization.withRegisteredClient(
                                client)
                        .id(authorizationId)
                        .principalName(principalName)
                        .authorizationGrantType(
                                org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
                        .authorizedScopes(Set.of("openid"))
                        .token(accessToken)
                        .refreshToken(refreshToken)
                        .build();
        this.authorizationService.save(authorization);
    }

    @Autowired
    private org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService authorizationService;

    private Integer authorizationRows(String appId) {
        return this.jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM oauth2_authorization WHERE registered_client_id = ?", Integer.class, appId);
    }

    private java.util.List<String> familyStatuses(String appId) {
        return this.jdbcTemplate.queryForList(
                "SELECT status FROM jauth_token_family WHERE registered_client_id = ?", String.class, appId);
    }
}
