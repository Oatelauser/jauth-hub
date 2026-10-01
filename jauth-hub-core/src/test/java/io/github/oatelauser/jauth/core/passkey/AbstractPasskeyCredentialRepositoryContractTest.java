package io.github.oatelauser.jauth.core.passkey;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oatelauser.jauth.core.audit.AuditEvent;
import io.github.oatelauser.jauth.core.audit.AuditEventPublisher;
import io.github.oatelauser.jauth.core.audit.AuditEventType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.webauthn.api.AuthenticatorTransport;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;

/**
 * Passkey 凭据仓储契约测试（抽象基类）：InMemory 与 JDBC 两实现同一套断言——字段全量回读（含 Base64 attestation
 * 往返）、更新不重复发注册事件（SS7 登录路径以 save 刷 signCount）、删除发移除事件且幂等删除静默。
 *
 * @author oatelauser
 */
abstract class AbstractPasskeyCredentialRepositoryContractTest {

    protected static final String USER_ID = "018f0000-0000-7000-8000-0000000000aa";

    /** 审计录制：断言"仓储路径真实发布"用。 */
    protected final List<AuditEvent> auditLog = new ArrayList<>();

    protected final AuditEventPublisher auditPublisher = this.auditLog::add;

    private UserCredentialRepository repository;

    protected abstract UserCredentialRepository createRepository();

    @BeforeEach
    void setUp() {
        this.repository = createRepository();
    }

    @Test
    void saveThenFindByCredentialIdRoundTripsAllFields() {
        ImmutableCredentialRecord record = newCredentialRecord(Bytes.random(), "my-passkey");

        repository().save(record);

        CredentialRecord found = repository().findByCredentialId(record.getCredentialId());
        assertThat(found).isNotNull();
        assertThat(found.getCredentialId()).isEqualTo(record.getCredentialId());
        assertThat(found.getUserEntityUserId()).isEqualTo(record.getUserEntityUserId());
        assertThat(found.getCredentialType()).isEqualTo(PublicKeyCredentialType.PUBLIC_KEY);
        assertThat(found.getSignatureCount()).isEqualTo(42L);
        assertThat(found.isUvInitialized()).isTrue();
        assertThat(found.isBackupEligible()).isTrue();
        assertThat(found.isBackupState()).isFalse();
        assertThat(found.getTransports())
                .containsExactlyInAnyOrder(AuthenticatorTransport.USB, AuthenticatorTransport.INTERNAL);
        assertThat(found.getLabel()).isEqualTo("my-passkey");
        // 三段二进制列（公钥 + attestation 两列）经 Base64 TEXT 往返后字节全等
        assertThat(found.getPublicKey().getBytes())
                .isEqualTo(record.getPublicKey().getBytes());
        assertThat(found.getAttestationObject()).isEqualTo(record.getAttestationObject());
        assertThat(found.getAttestationClientDataJSON()).isEqualTo(record.getAttestationClientDataJSON());
        assertThat(found.getCreated()).isEqualTo(Instant.parse("2026-10-01T08:00:00Z"));
        assertThat(found.getLastUsed()).isEqualTo(Instant.parse("2026-10-01T08:00:05.250Z"));
    }

    @Test
    void saveThenFindByUserIdReturnsTheRecord() {
        ImmutableCredentialRecord record = newCredentialRecord(Bytes.random(), "work-key");

        repository().save(record);

        assertThat(repository().findByUserId(record.getUserEntityUserId()))
                .hasSize(1)
                .first()
                .satisfies(found -> assertThat(found.getCredentialId()).isEqualTo(record.getCredentialId()));
    }

    @Test
    void saveNewCredentialPublishesRegistrationEvent() {
        repository().save(newCredentialRecord(Bytes.random(), "my-passkey"));

        assertThat(eventsOfType(AuditEventType.PASSKEY_REGISTERED)).hasSize(1);
        assertThat(eventsOfType(AuditEventType.PASSKEY_REGISTERED).get(0).actorUserId())
                .isEqualTo(USER_ID);
    }

    @Test
    void updatePathRefreshesRecordWithoutDuplicateRegistrationEvent() {
        ImmutableCredentialRecord record = newCredentialRecord(Bytes.random(), "my-passkey");
        repository().save(record);

        // SS7 登录路径形态：fromCredentialRecord 重建（signCount 前进、lastUsed 刷新、attestation 保持）
        CredentialRecord refreshed = ImmutableCredentialRecord.fromCredentialRecord(
                        repository().findByCredentialId(record.getCredentialId()))
                .signatureCount(43L)
                .lastUsed(Instant.parse("2026-10-01T09:00:00Z"))
                .build();
        repository().save(refreshed);

        assertThat(eventsOfType(AuditEventType.PASSKEY_REGISTERED)).hasSize(1);
        CredentialRecord found = repository().findByCredentialId(record.getCredentialId());
        assertThat(found.getSignatureCount()).isEqualTo(43L);
        assertThat(found.getLastUsed()).isEqualTo(Instant.parse("2026-10-01T09:00:00Z"));
        assertThat(found.getCreated()).isEqualTo(Instant.parse("2026-10-01T08:00:00Z"));
    }

    @Test
    void deleteRemovesCredentialAndPublishesRemovalEvent() {
        ImmutableCredentialRecord record = newCredentialRecord(Bytes.random(), "my-passkey");
        repository().save(record);

        repository().delete(record.getCredentialId());

        assertThat(repository().findByCredentialId(record.getCredentialId())).isNull();
        assertThat(repository().findByUserId(record.getUserEntityUserId())).isEmpty();
        assertThat(eventsOfType(AuditEventType.PASSKEY_REMOVED)).hasSize(1);
    }

    @Test
    void deleteMissingCredentialIsSilent() {
        Bytes unknownCredentialId = Bytes.random();

        repository().delete(unknownCredentialId);

        assertThat(this.auditLog).isEmpty();
    }

    @Test
    void findMissingReturnsNullOrEmpty() {
        assertThat(repository().findByCredentialId(Bytes.random())).isNull();
        assertThat(repository()
                        .findByUserId(JauthUserEntityRepository.userHandle("018f0000-0000-7000-8000-0000000000bb")))
                .isEmpty();
    }

    protected final UserCredentialRepository repository() {
        return this.repository;
    }

    protected final List<AuditEvent> eventsOfType(AuditEventType type) {
        return this.auditLog.stream().filter(event -> event.type() == type).toList();
    }

    protected final ImmutableCredentialRecord newCredentialRecord(Bytes credentialId, String label) {
        return ImmutableCredentialRecord.builder()
                .credentialId(credentialId)
                .userEntityUserId(JauthUserEntityRepository.userHandle(USER_ID))
                .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
                .publicKey(new ImmutablePublicKeyCose(Bytes.random().getBytes()))
                .signatureCount(42L)
                .uvInitialized(true)
                .transports(Set.of(AuthenticatorTransport.USB, AuthenticatorTransport.INTERNAL))
                .backupEligible(true)
                .backupState(false)
                .attestationObject(Bytes.random())
                .attestationClientDataJSON(Bytes.random())
                .created(Instant.parse("2026-10-01T08:00:00Z"))
                .lastUsed(Instant.parse("2026-10-01T08:00:05.250Z"))
                .label(label)
                .build();
    }
}
