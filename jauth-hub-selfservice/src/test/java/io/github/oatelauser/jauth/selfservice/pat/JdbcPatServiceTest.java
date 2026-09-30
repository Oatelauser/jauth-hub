package io.github.oatelauser.jauth.selfservice.pat;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oatelauser.jauth.core.token.TokenHash;
import io.github.oatelauser.jauth.selfservice.support.IntegrationTestSupport;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link JdbcPatService} 契约跑批 + DB 列级断言：token_sha256 落哈希不落明文（"落库即哈希"纪律的存储侧证明），
 * 吊销是 UPDATE 状态行（留痕）而非 DELETE。
 *
 * @author oatelauser
 */
class JdbcPatServiceTest extends AbstractPatServiceContractTest {

    private JdbcTemplate jdbcTemplate;

    private JdbcPatService service;

    @BeforeEach
    void setUp() {
        this.jdbcTemplate = IntegrationTestSupport.migratedJdbcTemplate("selfservice-pat");
        seedUser(USER_ID, "contract-user-a");
        seedUser(OTHER_USER_ID, "contract-user-b");
        this.service = new JdbcPatService(this.jdbcTemplate, fixedClock());
    }

    /** 契约用的占位用户行（jauth_pat.user_fk 外键要求用户先在池）。 */
    private void seedUser(String id, String username) {
        this.jdbcTemplate.update(
                "INSERT INTO jauth_user (id, username, password_hash, role, status, created_at)"
                        + " VALUES (?, ?, 'placeholder-hash-not-real', 'USER', 'ACTIVE', ?)",
                id,
                username,
                java.sql.Timestamp.from(T0));
    }

    @Override
    protected PatService service() {
        return this.service;
    }

    @Test
    void databaseColumnHoldsHashNotPlaintext() {
        PatService.PatIssuance issuance = this.service.create(USER_ID, "哈希锚", SCOPES, Duration.ofDays(90));

        String storedHash = this.jdbcTemplate.queryForObject(
                "SELECT token_sha256 FROM jauth_pat WHERE id = ?",
                String.class,
                issuance.record().id());
        assertThat(storedHash).isEqualTo(TokenHash.sha256Hex(issuance.plaintextToken()));
        assertThat(storedHash).doesNotContain(issuance.plaintextToken());
    }

    @Test
    void revokeUpdatesStatusInsteadOfDeleting() {
        PatService.PatIssuance issuance = this.service.create(USER_ID, "状态行", SCOPES, Duration.ofDays(90));

        this.service.revoke(USER_ID, issuance.record().id());

        String status = this.jdbcTemplate.queryForObject(
                "SELECT status FROM jauth_pat WHERE id = ?",
                String.class,
                issuance.record().id());
        assertThat(status).isEqualTo(PatStatus.REVOKED.name());
    }

    @Test
    void scopesRoundTripThroughJoinedColumn() {
        PatService.PatIssuance issuance =
                this.service.create(USER_ID, "范围回读", Set.of("openid", "email"), Duration.ofDays(90));

        String storedScopes = this.jdbcTemplate.queryForObject(
                "SELECT scopes FROM jauth_pat WHERE id = ?",
                String.class,
                issuance.record().id());
        assertThat(storedScopes).contains("openid", "email");
        assertThat(this.service.listActive(USER_ID).get(0).scopes()).containsExactlyInAnyOrder("openid", "email");
    }

    @Test
    void legacyRowWithoutNameSurvivesV7AndListsAsNull() {
        // V7 前的存量行 name 为 NULL：不炸、列表照常返回（展示层回退"未命名"）
        this.jdbcTemplate.update(
                "INSERT INTO jauth_pat (id, user_id, token_sha256, token_prefix, scopes, expires_at,"
                        + " status, created_at) VALUES ('legacy-pat-9', ?, 'bbbb', 'jpat_legacy',"
                        + " 'openid', CURRENT_TIMESTAMP, 'ACTIVE', CURRENT_TIMESTAMP)",
                USER_ID);
        this.service.create(USER_ID, "新名", Set.of("openid"), Duration.ofDays(30));

        List<PatRecord> active = this.service.listActive(USER_ID);
        assertThat(active).extracting(PatRecord::name).containsExactlyInAnyOrder("新名", null);
    }

    @Test
    void nameRoundTripsThroughNameColumn() {
        this.service.create(USER_ID, "名称列往返", SCOPES, Duration.ofDays(90));

        String storedName =
                this.jdbcTemplate.queryForObject("SELECT name FROM jauth_pat WHERE user_id = ?", String.class, USER_ID);
        assertThat(storedName).isEqualTo("名称列往返");
    }
}
