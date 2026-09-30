package io.github.oatelauser.jauth.core.org;

import io.github.oatelauser.jauth.core.audit.AuditEventPublisher;

/**
 * {@link InstallationService} 内存实现契约测试（org 装配复用 {@link InMemoryOrgServiceTest} 同套基建）。
 *
 * @author oatelauser
 */
class InMemoryInstallationServiceTest extends AbstractInstallationServiceContractTest {

    @Override
    protected OrgDomainFixture createFixture() {
        AuditEventPublisher recorder = this.auditLog::add;
        InMemoryOrgRepository orgRepository = new InMemoryOrgRepository();
        OrgService orgService = new OrgService(orgRepository, recorder, this.clock);
        InMemoryInstallationRepository installationRepository = new InMemoryInstallationRepository();
        return new OrgDomainFixture(
                orgRepository,
                installationRepository,
                orgService,
                new InstallationService(
                        installationRepository, orgRepository, orgService, this.clientStub, recorder, this.clock));
    }
}
