package io.github.oatelauser.jauth.core.passkey;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.web.webauthn.api.AuthenticatorTransport;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;

/**
 * 凭据行 ↔ {@link CredentialRecord} 转换器（{@link JdbcPasskeyCredentialRepository} 专用）。
 *
 * <p>列纪律（V8）：credentialId/userId 分别为 base64url 串与 UUIDv7 原串；公钥与 attestation 两列 Base64 TEXT
 * （V4 jwk 先例）；transports 逗号连接（空集落 NULL，读取时 NULL/空白 → 空集，不重演官方实现读 NULL 即 NPE 的脆点）。
 *
 * @author oatelauser
 */
final class CredentialRecordRowMapper implements RowMapper<CredentialRecord> {

    /** 凭据表全列（V2 的 7 列 + V8 补的 8 列），读取序即此序。 */
    static final String COLUMN_NAMES = "id, user_id, credential_id, public_key, sign_count, created_at, last_used_at, "
            + "label, credential_type, backup_eligible, backup_state, uv_initialized, transports, "
            + "attestation_object, attestation_client_data_json";

    /** transports 列的逗号连接序列化（空集 → NULL，与读取侧 null 安全配对）。 */
    static @Nullable String encodeTransports(Set<AuthenticatorTransport> transports) {
        if (transports == null || transports.isEmpty()) {
            return null;
        }
        return transports.stream().map(AuthenticatorTransport::getValue).collect(Collectors.joining(","));
    }

    static Set<AuthenticatorTransport> decodeTransports(@Nullable String joined) {
        if (joined == null || joined.isBlank()) {
            return Set.of();
        }
        Set<AuthenticatorTransport> transports = new LinkedHashSet<>();
        for (String value : joined.split(",")) {
            transports.add(AuthenticatorTransport.valueOf(value));
        }
        return transports;
    }

    static String encodeBinary(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    static byte @Nullable [] decodeBinary(@Nullable String base64) {
        return base64 == null ? null : Base64.getDecoder().decode(base64);
    }

    static @Nullable Timestamp toTimestamp(@Nullable Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    @Override
    public CredentialRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        byte[] publicKey = decodeBinary(rs.getString("public_key"));
        byte[] attestationObject = decodeBinary(rs.getString("attestation_object"));
        byte[] clientDataJson = decodeBinary(rs.getString("attestation_client_data_json"));
        String credentialType = rs.getString("credential_type");
        return ImmutableCredentialRecord.builder()
                .credentialId(Bytes.fromBase64(rs.getString("credential_id")))
                .userEntityUserId(new Bytes(rs.getString("user_id").getBytes(StandardCharsets.UTF_8)))
                .publicKey(new ImmutablePublicKeyCose(publicKey))
                .signatureCount(rs.getLong("sign_count"))
                .uvInitialized(rs.getBoolean("uv_initialized"))
                .backupEligible(rs.getBoolean("backup_eligible"))
                .backupState(rs.getBoolean("backup_state"))
                .credentialType(credentialType == null ? null : PublicKeyCredentialType.valueOf(credentialType))
                .transports(decodeTransports(rs.getString("transports")))
                .attestationObject(attestationObject == null ? null : new Bytes(attestationObject))
                .attestationClientDataJSON(clientDataJson == null ? null : new Bytes(clientDataJson))
                .created(rs.getTimestamp("created_at").toInstant())
                .lastUsed(rs.getTimestamp("last_used_at").toInstant())
                .label(rs.getString("label"))
                .build();
    }
}
