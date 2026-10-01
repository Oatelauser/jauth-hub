package io.github.oatelauser.jauth.core.passkey;

import io.github.oatelauser.jauth.core.audit.AuditEventPublisher;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;

/**
 * {@link UserCredentialRepository} 的内存实现：memory 存储模式的 passkey 凭据（demo/嵌入轻量场景），重启即失。
 *
 * <p>形态对齐框架 MapUserCredentialRepository（双索引：凭据 id 主索引 + 用户句柄 → 凭据 id 集），
 * 审计与插入判定继承 {@link AbstractPasskeyCredentialRepository}。
 *
 * @author oatelauser
 */
public class InMemoryPasskeyCredentialRepository extends AbstractPasskeyCredentialRepository {

    // 并发写仓储（注册/登录/管理页多请求线程）：对齐全仓 InMemory* 仓储的 ConcurrentHashMap 先例
    private final Map<Bytes, CredentialRecord> credentialsById = new ConcurrentHashMap<>();

    private final Map<Bytes, Set<Bytes>> credentialIdsByUserId = new ConcurrentHashMap<>();

    public InMemoryPasskeyCredentialRepository(AuditEventPublisher auditPublisher) {
        super(auditPublisher);
    }

    @Override
    public @Nullable CredentialRecord findByCredentialId(Bytes credentialId) {
        return this.credentialsById.get(credentialId);
    }

    @Override
    public List<CredentialRecord> findByUserId(Bytes userId) {
        // 并发删凭据时索引集与主表存在瞬时不一致（删的是主表行），null 过滤防 NPE 进 UI
        return this.credentialIdsByUserId.getOrDefault(userId, Set.of()).stream()
                .map(this.credentialsById::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    protected void doSave(CredentialRecord credentialRecord) {
        this.credentialsById.put(credentialRecord.getCredentialId(), credentialRecord);
        this.credentialIdsByUserId
                .computeIfAbsent(credentialRecord.getUserEntityUserId(), id -> ConcurrentHashMap.newKeySet())
                .add(credentialRecord.getCredentialId());
    }

    @Override
    protected void doDelete(Bytes credentialId) {
        CredentialRecord removed = this.credentialsById.remove(credentialId);
        if (removed != null) {
            Set<Bytes> credentialIds = this.credentialIdsByUserId.get(removed.getUserEntityUserId());
            if (credentialIds != null) {
                credentialIds.remove(credentialId);
            }
        }
    }
}
