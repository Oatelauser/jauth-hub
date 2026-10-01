package io.github.oatelauser.jauth.core.flyway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.oatelauser.jauth.core.support.IntegrationTestSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Flyway 迁移单测：H2(PostgreSQL 兼容模式) 上从零跑完 V1-V8，验证全部业务表齐建、 框架三表 owner CHECK 生效、spring_session 两表
 * vendored DDL 可执行。
 *
 * <p>表清单 14 张 = 框架 3 + 自有 9（含 B2 补定的 jauth_jwk）+ vendored 2，对齐 SPEC §3 "14 表"。
 *
 * @author oatelauser
 */
class FlywayMigrationTest {

    private static final List<String> EXPECTED_TABLES = List.of(
            "oauth2_registered_client",
            "oauth2_authorization",
            "oauth2_authorization_consent",
            "jauth_user",
            "jauth_user_credential",
            "jauth_org",
            "jauth_org_member",
            "jauth_installation",
            "jauth_pat",
            "jauth_token_family",
            "jauth_audit_event",
            "jauth_jwk",
            "spring_session",
            "spring_session_attributes");

    private final JdbcTemplate jdbcTemplate = IntegrationTestSupport.migratedJdbcTemplate("jauth-flyway-migration");

    @Test
    void migratesAllExpectedTables() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema ="
                        + " 'public' AND table_name <> 'flyway_schema_history'",
                String.class);
        assertThat(tables).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES);
    }

    @Test
    void flywayHistoryRecordsAllMigrations() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE type = 'SQL'", Integer.class);
        assertThat(count).isEqualTo(8);
    }

    @Test
    void installationRequestColumnsExist() {
        // V6 两步制补列：requested_by/requested_scopes（B8 安装请求域）
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name =" + " 'jauth_installation'",
                String.class);
        assertThat(columns).contains("requested_by", "requested_scopes");
    }

    @Test
    void patNameColumnExistsAndDefaultsToNull() {
        // V7 补列（B10）：名称可空，存量行不炸（回退展示"未命名"）
        jdbcTemplate.update("INSERT INTO jauth_user (id, username, password_hash, role, status, created_at)"
                + " VALUES ('legacy-user-1', 'legacy-user', 'placeholder-hash-not-real',"
                + " 'USER', 'ACTIVE', CURRENT_TIMESTAMP)");
        jdbcTemplate.update("INSERT INTO jauth_pat (id, user_id, token_sha256, token_prefix, scopes, expires_at,"
                + " status, created_at) VALUES ('legacy-pat-1', 'legacy-user-1',"
                + " 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',"
                + " 'jpat_legacy', 'openid', CURRENT_TIMESTAMP, 'ACTIVE', CURRENT_TIMESTAMP)");
        String name = jdbcTemplate.queryForObject("SELECT name FROM jauth_pat WHERE id = 'legacy-pat-1'", String.class);
        assertThat(name).isNull();
    }

    @Test
    void passkeyCredentialColumnsExistWithDefaults() {
        // V8 补列（v1.2 C1）：SS7 CredentialRecord 字段面；存量行（空表）带默认无感
        jdbcTemplate.update("INSERT INTO jauth_user (id, username, password_hash, role, status, created_at)"
                + " VALUES ('pk-user-1', 'pk-user', 'placeholder-hash-not-real',"
                + " 'USER', 'ACTIVE', CURRENT_TIMESTAMP)");
        jdbcTemplate.update("INSERT INTO jauth_user_credential (id, user_id, credential_id, public_key,"
                + " sign_count, created_at) VALUES ('pk-cred-1', 'pk-user-1', 'cred-id-1', 'cGJr', 0,"
                + " CURRENT_TIMESTAMP)");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT credential_type, backup_eligible, backup_state, uv_initialized, transports,"
                        + " attestation_object, attestation_client_data_json, label FROM jauth_user_credential"
                        + " WHERE id = 'pk-cred-1'");
        assertThat(row.get("credential_type")).isEqualTo("public-key");
        assertThat(row.get("backup_eligible")).isEqualTo(Boolean.FALSE);
        assertThat(row.get("backup_state")).isEqualTo(Boolean.FALSE);
        assertThat(row.get("uv_initialized")).isEqualTo(Boolean.FALSE);
        assertThat(row.get("transports")).isNull();
        assertThat(row.get("attestation_object")).isNull();
        assertThat(row.get("attestation_client_data_json")).isNull();
        assertThat(row.get("label")).isNull();
    }

    @Test
    void ownerColumnsEnforceAtMostOneSet() {
        // 皆空 = 平台内置，合法
        jdbcTemplate.update("INSERT INTO oauth2_registered_client (id, client_id, client_name,"
                + " client_authentication_methods, authorization_grant_types, scopes,"
                + " client_settings, token_settings) VALUES ('builtin-1', 'builtin-client',"
                + " 'builtin', 'none', 'client_credentials', 'introspect', '{}', '{}')");
        // 皆设 = 一个应用不能同时归属个人与组织，必须拒绝
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO oauth2_registered_client (id, client_id,"
                        + " client_name, client_authentication_methods,"
                        + " authorization_grant_types, scopes, client_settings,"
                        + " token_settings, owner_user_id, owner_org_id) VALUES"
                        + " ('bad-1', 'bad-client', 'bad', 'none',"
                        + " 'client_credentials', 'introspect', '{}', '{}',"
                        + " '018f0000-0000-7000-8000-00000000000a',"
                        + " '018f0000-0000-7000-8000-00000000000b')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sessionTablesAcceptRows() {
        jdbcTemplate.update("INSERT INTO spring_session (primary_id, session_id, creation_time,"
                + " last_access_time, max_inactive_interval, expiry_time) VALUES ('sess-1',"
                + " 'sess-1', 1, 1, 1800, 1801000)");
        jdbcTemplate.update("INSERT INTO spring_session_attributes (session_primary_id, attribute_name,"
                + " attribute_bytes) VALUES ('sess-1', 'attr', X'00FF')");
        Integer attributeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM spring_session_attributes WHERE session_primary_id =" + " 'sess-1'",
                Integer.class);
        assertThat(attributeCount).isEqualTo(1);
    }

    @Test
    void jwkTableRejectsDuplicateKid() {
        insertJwkRow("jwk-1", "kid-dup", "ACTIVE");
        // kid 唯一 = 并发双实例同代轮转的兜底约束（JwkRotationService 以冲突即放弃消费它）
        assertThatThrownBy(() -> insertJwkRow("jwk-2", "kid-dup", "RETIRING"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void tokenFamilyAcceptsGenerationsButRejectsDuplicateHash() {
        // registered_client_id 有外键（V2），先落一行宿主客户端
        jdbcTemplate.update("INSERT INTO oauth2_registered_client (id, client_id, client_name,"
                + " client_authentication_methods, authorization_grant_types, scopes,"
                + " client_settings, token_settings) VALUES ('client-a', 'client-a',"
                + " 'client-a', 'none', 'authorization_code', 'openid', '{}', '{}')");
        insertFamilyRow("fam-1", "alice", "client-a", 1, "ACTIVE", "aa".repeat(32));
        // V5 行模型：同族多行（一轮转一行）合法，历史哈希因此得以保留供重放检测
        insertFamilyRow("fam-2", "alice", "client-a", 2, "SUPERSEDED", "bb".repeat(32));
        // refresh_token_hash 全局唯一：同一令牌不能记两行
        assertThatThrownBy(() -> insertFamilyRow("fam-3", "alice", "client-a", 3, "ACTIVE", "aa".repeat(32)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertJwkRow(String id, String kid, String status) {
        jdbcTemplate.update(
                "INSERT INTO jauth_jwk (id, kid, algorithm, key_size, public_key, private_key,"
                        + " status, created_at) VALUES (?, ?, 'RS256', 2048, 'pub', 'priv', ?,"
                        + " CURRENT_TIMESTAMP)",
                id,
                kid,
                status);
    }

    private void insertFamilyRow(
            String id, String principalName, String clientId, int generation, String status, String refreshTokenHash) {
        jdbcTemplate.update(
                "INSERT INTO jauth_token_family (id, principal_name, registered_client_id,"
                        + " generation, status, refresh_token_hash, created_at) VALUES (?, ?, ?, ?,"
                        + " ?, ?, CURRENT_TIMESTAMP)",
                id,
                principalName,
                clientId,
                generation,
                status,
                refreshTokenHash);
    }
}
