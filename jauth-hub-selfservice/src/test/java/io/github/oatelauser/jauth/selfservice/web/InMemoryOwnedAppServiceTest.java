package io.github.oatelauser.jauth.selfservice.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oatelauser.jauth.core.client.ClientOwner;
import io.github.oatelauser.jauth.core.client.InMemoryClientOwnerResolver;
import io.github.oatelauser.jauth.core.token.InMemoryTokenFamilyService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * {@link InMemoryOwnedAppService} 契约跑批 + memory 语义断言：owner 登记进 {@link InMemoryClientOwnerResolver}
 * （ceiling/consent 的 org 上下文读取面），client 落框架内存仓库、重启即失。
 *
 * @author oatelauser
 */
class InMemoryOwnedAppServiceTest extends AbstractOwnedAppServiceContractTest {

    private final InMemoryRegisteredClientRepository clientRepository =
            new InMemoryRegisteredClientRepository(RegisteredClient.withId("seed-platform-1")
                    .clientId("seed-platform")
                    .clientName("platform seed")
                    .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                    .build());

    private final InMemoryClientOwnerResolver ownerResolver = new InMemoryClientOwnerResolver();

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private final InMemoryOwnedAppService service = new InMemoryOwnedAppService(
            this.clientRepository,
            this.ownerResolver,
            new InMemoryTokenFamilyService(),
            this.passwordEncoder,
            scopeCatalog(),
            fixedClock());

    @Override
    protected String currentSecretHash(String appId) {
        // 内存实现：RegisteredClient 即时对象携带的就是轮转时写入的编码哈希，直接回读
        return this.clientRepository.findById(appId).getClientSecret();
    }

    @Override
    protected OwnedAppService service() {
        return this.service;
    }

    @Override
    protected RegisteredClientRepository clientRepository() {
        return this.clientRepository;
    }

    @Override
    protected PasswordEncoder passwordEncoder() {
        return this.passwordEncoder;
    }

    @Test
    void registerRecordsOwnerInResolver() {
        OwnedAppService.Registration registration = this.service.register(USER_ID, "登记断言", REDIRECTS, false);

        // B9 读取面：ceiling/consent 页经 resolver 读归属，memory 模式注册后即登记录
        assertThat(this.ownerResolver.findOwner(registration.app().id())).isEqualTo(ClientOwner.ofUser(USER_ID));
    }

    /** B11 org 路径：memory 模式注册 org 应用后登记 ofOrg 归属（ceiling/consent 的 org 上下文由此可读）。 */
    @Test
    void registerOrgRecordsOrgOwnerInResolver() {
        OwnedAppService.Registration registration = this.service.registerOrg(ORG_ID, "组织门户", REDIRECTS, false);

        assertThat(this.ownerResolver.findOwner(registration.app().id())).isEqualTo(ClientOwner.ofOrg(ORG_ID));
    }

    @Test
    void platformSeedStaysOutOfPersonalList() {
        // 未登记归属（平台语义）的种子客户端不进个人列表——列表只出自注册记录
        this.service.register(USER_ID, "个人应用", REDIRECTS, false);

        assertThat(this.service.list(USER_ID))
                .extracting(OwnedAppService.OwnedApp::name)
                .containsExactly("个人应用");
    }
}
