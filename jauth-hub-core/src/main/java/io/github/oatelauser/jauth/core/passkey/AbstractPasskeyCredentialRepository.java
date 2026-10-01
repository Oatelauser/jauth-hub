package io.github.oatelauser.jauth.core.passkey;

import io.github.oatelauser.jauth.core.audit.AuditEvent;
import io.github.oatelauser.jauth.core.audit.AuditEventPublisher;
import io.github.oatelauser.jauth.core.audit.AuditEventType;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.util.Assert;

/**
 * Passkey 凭据仓储的审计模板基类（memory/jdbc 双实现共用 save/delete 的生命周期审计决策）。
 *
 * <p><b>注册/移除事件必须区分插入与更新</b>：SS 7.1.1 的
 * {@code Webauthn4JRelyingPartyOperations.authenticate()} 在每次 passkey 登录成功后以 save() 刷新
 * signCount/lastUsed——save 是"注册 + 登录后刷新"共用入口，不能无差别打
 * {@link AuditEventType#PASSKEY_REGISTERED}。以"credentialId 是否已存在"判定插入，仅插入路径发事件，
 * 与审计"只记生命周期"的纪律对齐（SPEC §3）。
 *
 * <p>delete 仅在实际删除到行时发事件（幂等删除不产生噪音）。
 *
 * @author oatelauser
 */
public abstract class AbstractPasskeyCredentialRepository implements UserCredentialRepository {

    private final AuditEventPublisher auditPublisher;

    protected AbstractPasskeyCredentialRepository(AuditEventPublisher auditPublisher) {
        Assert.notNull(auditPublisher, "auditPublisher cannot be null");
        this.auditPublisher = auditPublisher;
    }

    @Override
    public final void save(CredentialRecord credentialRecord) {
        Assert.notNull(credentialRecord, "credentialRecord cannot be null");
        boolean isNewCredential = findByCredentialId(credentialRecord.getCredentialId()) == null;
        doSave(credentialRecord);
        if (isNewCredential) {
            this.auditPublisher.publish(AuditEvent.of(
                    AuditEventType.PASSKEY_REGISTERED,
                    JauthUserEntityRepository.userHandle(credentialRecord.getUserEntityUserId()),
                    "credential",
                    credentialRecord.getCredentialId().toBase64UrlString(),
                    "label=" + credentialRecord.getLabel()));
        }
    }

    @Override
    public final void delete(Bytes credentialId) {
        Assert.notNull(credentialId, "credentialId cannot be null");
        CredentialRecord existing = findByCredentialId(credentialId);
        doDelete(credentialId);
        if (existing != null) {
            this.auditPublisher.publish(AuditEvent.of(
                    AuditEventType.PASSKEY_REMOVED,
                    JauthUserEntityRepository.userHandle(existing.getUserEntityUserId()),
                    "credential",
                    credentialId.toBase64UrlString(),
                    null));
        }
    }

    /** 存储侧 upsert（同 credentialId 覆盖更新，语义对齐框架官方 JdbcUserCredentialRepository）。 */
    protected abstract void doSave(CredentialRecord credentialRecord);

    protected abstract void doDelete(Bytes credentialId);
}
