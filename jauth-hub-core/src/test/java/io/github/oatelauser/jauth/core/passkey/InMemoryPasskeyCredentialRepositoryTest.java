package io.github.oatelauser.jauth.core.passkey;

import org.springframework.security.web.webauthn.management.UserCredentialRepository;

/**
 * InMemory 实现跑凭据仓储契约（memory 存储模式）。
 *
 * @author oatelauser
 */
class InMemoryPasskeyCredentialRepositoryTest extends AbstractPasskeyCredentialRepositoryContractTest {

    @Override
    protected UserCredentialRepository createRepository() {
        return new InMemoryPasskeyCredentialRepository(this.auditPublisher);
    }
}
