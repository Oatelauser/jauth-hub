package io.github.oatelauser.jauth.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.oatelauser.jauth.core.token.TokenHash;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.util.UuidV7;
import java.sql.Timestamp;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * PAT 内省接入 + /me 平台端点集成测试（B7 任务 3/4，jdbc 模式——PAT 为持久化语义、memory 禁用）：
 * <ul>
 * <li>PAT 经 /introspect active:true，sub/username/scope/client_id（PAT 专用标识）齐全，last_used_at 前进
 * <li>/me：PAT 与授权令牌两种凭证 200 字段断言；无凭证 401 RFC 6750 形态；响应无 code/message 包装
 * <li>PAT 行以 JdbcTemplate 直插（种子面在 selfservice 模块，自有契约测试覆盖；本域只验叠加层接线）
 * </ul>
 *
 * @author oatelauser
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, classes = JauthHubStarterTestApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.autoconfigure.exclude=io.github.oatelauser.springplus.web.autoconfigure.ExceptionHandlingAutoConfiguration,"
                    + "io.github.oatelauser.springplus.web.autoconfigure.SpringPlusWebAutoConfiguration",
            "spring.datasource.url=jdbc:h2:mem:jauth-pat-me-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "jauth-hub.storage=jdbc",
            "jauth-hub.clients[0].client-id=me-introspector",
            "jauth-hub.clients[0].client-name=Introspector",
            "jauth-hub.clients[0].client-secret=introspector-secret",
            "jauth-hub.clients[0].grant-types[0]=client_credentials",
            "jauth-hub.clients[0].redirect-uris[0]=https://example.com/cb",
            "jauth-hub.clients[0].scopes[0]=openid"
        })
class PatIntrospectionAndMeIntegrationTest {

    /** 已知形状的 PAT 明文（测试自造，非真实签发面）；吊销用例用独立令牌防测试间状态串扰。 */
    private static final String PAT_TOKEN = "jpat_test-token-of-known-shape-43-chars-abcdefgh";

    private static final String REVOKED_PAT_TOKEN = "jpat_test-token-of-revoked-shape-43-char-xyz";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("PAT 经 /introspect：active + sub/username/scope/client_id 专用标识，last_used_at 前进")
    void patIntrospectionActiveAndEnriched() throws Exception {
        String userId = seedUserAndPat();

        MvcResult introspected = this.mockMvc
                .perform(post("/oauth2/introspect")
                        .with(httpBasic("me-introspector", "introspector-secret"))
                        .with(csrf())
                        .param("token", PAT_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.sub").value(userId))
                .andExpect(jsonPath("$.username").value("pat-owner"))
                .andExpect(jsonPath("$.client_id").value("jauth-pat"))
                .andExpect(jsonPath("$.scope").value("profile"))
                .andReturn();

        Timestamp lastUsed = new JdbcTemplate(this.dataSource)
                .queryForObject("SELECT last_used_at FROM jauth_pat WHERE user_id = ?", Timestamp.class, userId);
        assertThat(lastUsed).as("PAT 命中即回填最近使用（票 07：使用推导）").isNotNull();
        assertThat(introspected.getResponse().getContentAsString()).doesNotContain("\"error\"");
    }

    @Test
    @DisplayName("吊销/过期的 PAT 内省 inactive（叠加层只认 ACTIVE 且未过期）")
    void revokedPatIntrospectionInactive() throws Exception {
        String userId = seedUserAndPat();
        JdbcTemplate jdbc = new JdbcTemplate(this.dataSource);
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM jauth_pat WHERE token_sha256 = ?",
                Integer.class,
                TokenHash.sha256Hex(REVOKED_PAT_TOKEN));
        if (rows == null || rows == 0) {
            jdbc.update(
                    "INSERT INTO jauth_pat (id, user_id, token_sha256, token_prefix, scopes,"
                            + " expires_at, last_used_at, status, created_at) VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?)",
                    UuidV7.generate().toString(),
                    userId,
                    TokenHash.sha256Hex(REVOKED_PAT_TOKEN),
                    REVOKED_PAT_TOKEN.substring(0, 12),
                    "profile",
                    Timestamp.from(Instant.now().plusSeconds(3600)),
                    "REVOKED",
                    Timestamp.from(Instant.now()));
        }

        this.mockMvc
                .perform(post("/oauth2/introspect")
                        .with(httpBasic("me-introspector", "introspector-secret"))
                        .with(csrf())
                        .param("token", REVOKED_PAT_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    @DisplayName("/me：PAT 200 裸 JSON；无凭证 401 + WWW-Authenticate；响应无 code/message 包装")
    void meEndpointWithPatAndChallenge() throws Exception {
        String userId = seedUserAndPat();
        String body = this.mockMvc
                .perform(get("/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sub").value(userId))
                .andExpect(jsonPath("$.username").value("pat-owner"))
                .andExpect(jsonPath("$.scope").value("profile"))
                .andReturn()
                .getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).as("平台 API 裸 JSON，无统一响应外壳").doesNotContain("code").doesNotContain("message");

        this.mockMvc.perform(get("/me")).andExpect(status().isUnauthorized()).andExpect(result -> assertThat(
                        result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .startsWith("Bearer"));

        this.mockMvc
                .perform(get("/me").header(HttpHeaders.AUTHORIZATION, "Bearer jpat_unknown-token-value"))
                .andExpect(status().isUnauthorized())
                .andExpect(result -> assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                        .contains("invalid_token"));
    }

    /** 播用户 + 直插一行 ACTIVE PAT（token_sha256 = 已知明文的哈希）。幂等。 */
    private String seedUserAndPat() {
        JauthUser existing = this.userRepository.findByUsername("pat-owner");
        String userId;
        if (existing == null) {
            userId = UuidV7.generate().toString();
            this.userRepository.save(new JauthUser(
                    userId,
                    "pat-owner",
                    "placeholder-hash-not-real",
                    null,
                    null,
                    JauthUser.ROLE_USER,
                    JauthUser.STATUS_ACTIVE,
                    null,
                    Instant.now()));
        } else {
            userId = existing.id();
        }
        JdbcTemplate jdbc = new JdbcTemplate(this.dataSource);
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM jauth_pat WHERE token_sha256 = ?", Integer.class, TokenHash.sha256Hex(PAT_TOKEN));
        if (rows == null || rows == 0) {
            jdbc.update(
                    "INSERT INTO jauth_pat (id, user_id, token_sha256, token_prefix, scopes,"
                            + " expires_at, last_used_at, status, created_at) VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?)",
                    UuidV7.generate().toString(),
                    userId,
                    TokenHash.sha256Hex(PAT_TOKEN),
                    PAT_TOKEN.substring(0, 12),
                    "profile",
                    Timestamp.from(Instant.now().plusSeconds(3600)),
                    "ACTIVE",
                    Timestamp.from(Instant.now()));
        }
        return userId;
    }
}
