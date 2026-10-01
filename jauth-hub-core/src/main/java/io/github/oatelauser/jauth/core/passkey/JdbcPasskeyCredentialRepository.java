package io.github.oatelauser.jauth.core.passkey;

import io.github.oatelauser.jauth.core.audit.AuditEventPublisher;
import io.github.oatelauser.jauth.core.util.UuidV7;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.util.Assert;

/**
 * {@link UserCredentialRepository} 的 JDBC 实现（表 jauth_user_credential，V2 建 V8 补列，自持 SQL 不引 ORM）。
 *
 * <p>upsert 语义对齐框架官方 JdbcUserCredentialRepository：先 UPDATE 后 INSERT（0 行即新凭据）；
 * created_at 只在插入时落库（登录后的 signCount/lastUsed 刷新不重置注册时间）。列编解码见
 * {@link CredentialRecordRowMapper}。
 *
 * @author oatelauser
 */
public class JdbcPasskeyCredentialRepository extends AbstractPasskeyCredentialRepository {

    private static final RowMapper<CredentialRecord> ROW_MAPPER = new CredentialRecordRowMapper();

    private static final String FIND_BY_CREDENTIAL_ID_SQL =
            "SELECT " + CredentialRecordRowMapper.COLUMN_NAMES + " FROM jauth_user_credential WHERE credential_id = ?";

    private static final String FIND_BY_USER_ID_SQL =
            "SELECT " + CredentialRecordRowMapper.COLUMN_NAMES + " FROM jauth_user_credential WHERE user_id = ?";

    private static final String INSERT_SQL = "INSERT INTO jauth_user_credential ("
            + CredentialRecordRowMapper.COLUMN_NAMES + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_SQL = "UPDATE jauth_user_credential SET user_id = ?, public_key = ?,"
            + " sign_count = ?, last_used_at = ?, label = ?, credential_type = ?, backup_eligible = ?,"
            + " backup_state = ?, uv_initialized = ?, transports = ?, attestation_object = ?,"
            + " attestation_client_data_json = ? WHERE credential_id = ?";

    private static final String DELETE_SQL = "DELETE FROM jauth_user_credential WHERE credential_id = ?";

    private final JdbcOperations jdbcOperations;

    public JdbcPasskeyCredentialRepository(JdbcOperations jdbcOperations, AuditEventPublisher auditPublisher) {
        super(auditPublisher);
        Assert.notNull(jdbcOperations, "jdbcOperations cannot be null");
        this.jdbcOperations = jdbcOperations;
    }

    @Override
    public @Nullable CredentialRecord findByCredentialId(Bytes credentialId) {
        Assert.notNull(credentialId, "credentialId cannot be null");
        List<CredentialRecord> result =
                this.jdbcOperations.query(FIND_BY_CREDENTIAL_ID_SQL, ROW_MAPPER, credentialId.toBase64UrlString());
        return result.isEmpty() ? null : result.get(0);
    }

    @Override
    public List<CredentialRecord> findByUserId(Bytes userId) {
        Assert.notNull(userId, "userId cannot be null");
        return this.jdbcOperations.query(FIND_BY_USER_ID_SQL, ROW_MAPPER, JauthUserEntityRepository.userHandle(userId));
    }

    @Override
    protected void doSave(CredentialRecord record) {
        int updated = updateCredentialRecord(record);
        if (updated == 0) {
            insertCredentialRecord(record);
        }
    }

    @Override
    protected void doDelete(Bytes credentialId) {
        this.jdbcOperations.update(DELETE_SQL, credentialId.toBase64UrlString());
    }

    private void insertCredentialRecord(CredentialRecord record) {
        this.jdbcOperations.update(INSERT_SQL, ps -> {
            ps.setString(1, UuidV7.generate().toString());
            ps.setString(2, JauthUserEntityRepository.userHandle(record.getUserEntityUserId()));
            ps.setString(3, record.getCredentialId().toBase64UrlString());
            ps.setString(4, CredentialRecordRowMapper.encodeBinary(record.getPublicKey().getBytes()));
            ps.setLong(5, record.getSignatureCount());
            ps.setTimestamp(6, CredentialRecordRowMapper.toTimestamp(record.getCreated()));
            ps.setTimestamp(7, CredentialRecordRowMapper.toTimestamp(record.getLastUsed()));
            setCommonColumns(ps, record, 8);
        });
    }

    private int updateCredentialRecord(CredentialRecord record) {
        return this.jdbcOperations.update(UPDATE_SQL, ps -> {
            ps.setString(1, JauthUserEntityRepository.userHandle(record.getUserEntityUserId()));
            ps.setString(2, CredentialRecordRowMapper.encodeBinary(record.getPublicKey().getBytes()));
            ps.setLong(3, record.getSignatureCount());
            ps.setTimestamp(4, CredentialRecordRowMapper.toTimestamp(record.getLastUsed()));
            setCommonColumns(ps, record, 5);
            ps.setString(13, record.getCredentialId().toBase64UrlString());
        });
    }

    /** label/credential_type/布尔三列/transports/attestation 两列：插入（从 8）与更新（从 5）共用的一段绑定。 */
    private static void setCommonColumns(PreparedStatement ps, CredentialRecord record, int startIndex) throws SQLException {
        int index = startIndex;
        ps.setString(index++, record.getLabel());
        String credentialType = record.getCredentialType() == null ? null : record.getCredentialType().getValue();
        ps.setString(index++, credentialType);
        ps.setBoolean(index++, record.isBackupEligible());
        ps.setBoolean(index++, record.isBackupState());
        ps.setBoolean(index++, record.isUvInitialized());
        ps.setString(index++, CredentialRecordRowMapper.encodeTransports(record.getTransports()));
        setNullableBase64(ps, index++, record.getAttestationObject());
        setNullableBase64(ps, index, record.getAttestationClientDataJSON());
    }

    private static void setNullableBase64(PreparedStatement ps, int index, @Nullable Bytes bytes) throws SQLException {
        ps.setString(index, bytes == null ? null : CredentialRecordRowMapper.encodeBinary(bytes.getBytes()));
    }
}
