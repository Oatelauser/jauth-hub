package io.github.oatelauser.jauth.core.org;

import io.github.oatelauser.jauth.core.audit.AuditEventPublisher;

/**
 * {@link OrgService} + {@link InMemoryOrgRepository}/{@link InMemoryInstallationRepository} 契约测试。
 *
 * @author oatelauser
 */
class InMemoryOrgServiceTest extends AbstractOrgServiceContractTest {

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
