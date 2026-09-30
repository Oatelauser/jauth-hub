package io.github.oatelauser.jauth.core.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.oatelauser.jauth.core.audit.AuditEvent;
import io.github.oatelauser.jauth.core.audit.AuditEventType;
import io.github.oatelauser.jauth.core.response.JauthErrorCode;
import io.github.oatelauser.jauth.core.response.JauthException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * org 域契约测试（抽象基类）：同一套断言跑 InMemory 与 JDBC 两实现，保证行为一致。命名遵守 p3c 抽象类前缀
 * Abstract 约定（模式同 user 三件套契约测试）。
 *
 * @author oatelauser
 */
abstract class AbstractOrgServiceContractTest {

    protected static final String OWNER_ID = "018f0000-0000-7000-8000-000000000001";

    protected static final String MEMBER_ID = "018f0000-0000-7000-8000-000000000002";

    protected static final String OUTSIDER_ID = "018f0000-0000-7000-8000-000000000003";

    protected static final String CLIENT_ID = "install-client-1";

    /** 固定时钟：createdAt/approvedAt 断言可精确到相等（jdbc Timestamp 往返无漂移）。 */
    protected static final Instant FIXED_NOW = Instant.parse("2026-09-30T08:00:00Z");

    /** 审计录制：断言"服务路径真实发布"用。 */
    protected final List<AuditEvent> auditLog = new ArrayList<>();

    protected final Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    /** 客户端存在性桩：request 的 client 门只判存在性，真实客户端读写属 client 域测试。 */
    protected final RegisteredClientRepository clientStub = new RegisteredClientRepository() {
        @Override
        public void save(RegisteredClient registeredClient) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public RegisteredClient findById(String id) {
            return CLIENT_ID.equals(id)
                    ? RegisteredClient.withId(id)
                            .clientId(id)
                            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                            .build()
                    : null;
        }

        @Override
        public RegisteredClient findByClientId(String clientId) {
            return null;
        }
    };

    private OrgDomainFixture fixture;

    /** 双实现各自的装配（jdbc 侧另做 Flyway 建库 + 种子行）。 */
    protected record OrgDomainFixture(
            OrgRepository orgRepository,
            InstallationRepository installationRepository,
            OrgService orgService,
            InstallationService installationService) {}

    protected abstract OrgDomainFixture createFixture();

    @BeforeEach
    void setUp() {
        this.fixture = createFixture();
    }

    @Test
    void createMakesCreatorOwnerAndAudits() {
        Org org = fixture().orgService().create("acme", OWNER_ID);

        assertThat(org.name()).isEqualTo("acme");
        assertThat(org.id()).isNotBlank();
        assertThat(org.createdAt()).isEqualTo(FIXED_NOW);
        assertThat(fixture().orgService().isOwner(org.id(), OWNER_ID)).isTrue();
        assertThat(fixture().orgService().findByUser(OWNER_ID)).singleElement().satisfies(membership -> {
            assertThat(membership.orgId()).isEqualTo(org.id());
            assertThat(membership.orgName()).isEqualTo("acme");
            assertThat(membership.role()).isEqualTo(OrgRole.OWNER);
        });
        assertThat(eventsOfType(AuditEventType.ORG_CREATED)).singleElement().satisfies(event -> {
            assertThat(event.actorUserId()).isEqualTo(OWNER_ID);
            assertThat(event.targetType()).isEqualTo("org");
            assertThat(event.targetId()).isEqualTo(org.id());
        });
    }

    @Test
    void createRejectsDuplicateName() {
        fixture().orgService().create("acme", OWNER_ID);
        assertThatThrownBy(() -> fixture().orgService().create("acme", OUTSIDER_ID))
                .isInstanceOfSatisfying(
                        JauthException.class, ex -> assertThat(ex.getCode()).isEqualTo(JauthErrorCode.A0506.getCode()));
    }

    @Test
    void isOwnerFalseForMemberOutsiderAndUnknownOrg() {
        Org org = fixture().orgService().create("acme", OWNER_ID);
        fixture().orgRepository().saveMember(new OrgMember(org.id(), MEMBER_ID, OrgRole.MEMBER, FIXED_NOW));

        assertThat(fixture().orgService().isOwner(org.id(), MEMBER_ID)).isFalse();
        assertThat(fixture().orgService().isOwner(org.id(), OUTSIDER_ID)).isFalse();
        assertThat(fixture().orgService().isOwner("00000000-0000-7000-8000-0000000000ff", OWNER_ID))
                .isFalse();
    }

    @Test
    void findByUserListsAllOrgsWithRoles() {
        fixture().orgService().create("acme", OWNER_ID);
        Org second = fixture().orgService().create("globex", OUTSIDER_ID);
        fixture().orgRepository().saveMember(new OrgMember(second.id(), OWNER_ID, OrgRole.MEMBER, FIXED_NOW));

        assertThat(fixture().orgService().findByUser(OWNER_ID))
                .extracting(OrgMembership::orgName, OrgMembership::role)
                .containsExactlyInAnyOrder(tuple("acme", OrgRole.OWNER), tuple("globex", OrgRole.MEMBER));
    }

    protected final OrgDomainFixture fixture() {
        return this.fixture;
    }

    protected final List<AuditEvent> eventsOfType(AuditEventType type) {
        return this.auditLog.stream().filter(event -> event.type() == type).toList();
    }
}
