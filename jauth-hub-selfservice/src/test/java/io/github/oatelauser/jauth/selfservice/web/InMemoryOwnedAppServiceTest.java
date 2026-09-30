package io.github.oatelauser.jauth.selfservice.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oatelauser.jauth.core.client.ClientOwner;
import io.github.oatelauser.jauth.core.client.InMemoryClientOwnerResolver;
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
            this.clientRepository, this.ownerResolver, this.passwordEncoder, scopeCatalog(), fixedClock());

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

    @Test
    void platformSeedStaysOutOfPersonalList() {
        // 未登记归属（平台语义）的种子客户端不进个人列表——列表只出自注册记录
        this.service.register(USER_ID, "个人应用", REDIRECTS, false);

        assertThat(this.service.list(USER_ID))
                .extracting(OwnedAppService.OwnedApp::name)
                .containsExactly("个人应用");
    }
}
