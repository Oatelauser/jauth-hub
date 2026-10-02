package io.github.oatelauser.jauth.selfservice.web;

import io.github.oatelauser.jauth.core.client.ClientOwner;
import io.github.oatelauser.jauth.core.client.InMemoryClientOwnerResolver;
import io.github.oatelauser.jauth.core.response.JauthErrorCode;
import io.github.oatelauser.jauth.core.response.JauthException;
import io.github.oatelauser.jauth.core.scope.ScopeCatalog;
import io.github.oatelauser.jauth.core.token.InMemoryTokenFamilyService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.util.Assert;

/**
 * {@link OwnedAppService} 的内存实现（memory 存储模式，B10 起应用管理与 PAT 不同——memory 可用）：
 * client 本体落框架 {@link RegisteredClientRepository}（starter memory 模式即 InMemoryRegisteredClientRepository），
 * owner 归属经 {@link InMemoryClientOwnerResolver#put} 登记（ceiling/consent 的 org 上下文由此可读）。
 *
 * <p>列表：框架内存仓库无枚举面，本类自持"归属 → 注册的 client 主键"追加表（注册即记，重启随仓库一起失），
 * 列表行经 findById 回查仓库，单一真源仍是 client 本体。归属键是 {@link ClientOwner}（个人/组织两维度同表，
 * B11）。
 *
 * @author oatelauser
 */
public class InMemoryOwnedAppService extends OwnedAppService {

    private final RegisteredClientRepository clientRepository;

    private final BiConsumer<String, ClientOwner> ownerRegistrar;

    /** 族谱烧断（v1.3 D2 删除级联；内存授权对象随重启消亡，族谱先行烧断阻断重放探测误报）。 */
    private final InMemoryTokenFamilyService tokenFamilyService;

    /** "归属 → 注册的 client 主键"追加表：个人（ofUser）与 org（ofOrg）两维度同表（B11 起 owner 维度扩展）。 */
    private final Map<ClientOwner, List<String>> clientIdsByOwner = new ConcurrentHashMap<>();

    public InMemoryOwnedAppService(
            RegisteredClientRepository clientRepository,
            InMemoryClientOwnerResolver ownerResolver,
            InMemoryTokenFamilyService tokenFamilyService,
            PasswordEncoder passwordEncoder,
            ScopeCatalog scopeCatalog,
            Clock clock) {
        super(passwordEncoder, scopeCatalog, clock);
        this.clientRepository = clientRepository;
        // 委托以函数捕获（JdbcClientOwnerResolver 同款取舍）：登记表带 put 可变方法，存成员即 SpotBugs
        // EI_EXPOSE_REP2 暴露内部表示
        this.ownerRegistrar = ownerResolver::put;
        this.tokenFamilyService = tokenFamilyService;
    }

    @Override
    protected void persist(RegisteredClient client, ClientOwner owner) {
        this.clientRepository.save(client);
        this.ownerRegistrar.accept(client.getId(), owner);
        this.clientIdsByOwner
                .computeIfAbsent(owner, key -> new CopyOnWriteArrayList<>())
                .add(client.getId());
    }

    @Override
    public List<OwnedApp> list(String userId) {
        Assert.hasText(userId, "userId cannot be empty");
        return listByOwner(ClientOwner.ofUser(userId));
    }

    @Override
    public List<OwnedApp> listOrg(String orgId) {
        Assert.hasText(orgId, "orgId cannot be empty");
        return listByOwner(ClientOwner.ofOrg(orgId));
    }

    @Override
    protected OwnedApp persistSecretRotation(ClientOwner owner, String appId, String encodedSecret) {
        RegisteredClient client = requireOwnedClient(owner, appId);
        if (!client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)) {
            // 公开应用无 secret 可轮（与 JDBC 版 WHERE 并入机密性判定的同译：B0502）
            throw new JauthException(JauthErrorCode.B0502);
        }
        RegisteredClient rotated =
                RegisteredClient.from(client).clientSecret(encodedSecret).build();
        this.clientRepository.save(rotated);
        return viewOf(rotated);
    }

    @Override
    protected OwnedApp persistUpdate(ClientOwner owner, String appId, String name, Set<String> redirectUris) {
        RegisteredClient client = requireOwnedClient(owner, appId);
        // from() 继承旧回调且 redirectUri() 是追加语义——编辑须整组替换:Consumer 形态先清后填
        RegisteredClient updated = RegisteredClient.from(client)
                .clientName(name)
                .redirectUris(uris -> {
                    uris.clear();
                    uris.addAll(redirectUris);
                })
                .build();
        this.clientRepository.save(updated);
        return viewOf(updated);
    }

    @Override
    protected void persistDelete(ClientOwner owner, String appId) {
        requireOwnedClient(owner, appId);
        // memory 语义边界：框架内存仓库无删除面——登记表除名（列表即刻不可见）+ 族谱烧断（阻断重放
        // 探测路径）；client 本体与内存授权对象随重启消亡（SPEC §3 memory 既定语义，非缺陷）
        this.clientIdsByOwner.getOrDefault(owner, List.of()).remove(appId);
        this.tokenFamilyService.burnAllByClient(appId);
    }

    /** 归属收紧的应用回查：登记表不含/仓库已失皆 B0502（他人 id 不可触达）。 */
    private RegisteredClient requireOwnedClient(ClientOwner owner, String appId) {
        if (!this.clientIdsByOwner.getOrDefault(owner, List.of()).contains(appId)) {
            throw new JauthException(JauthErrorCode.B0502);
        }
        RegisteredClient client = this.clientRepository.findById(appId);
        if (client == null) {
            throw new JauthException(JauthErrorCode.B0502);
        }
        return client;
    }

    /** 列表单核：按归属取登记主键 → 仓库回查本体（单一真源是 client 本体，登记表只是枚举面）。 */
    private List<OwnedApp> listByOwner(ClientOwner owner) {
        List<OwnedApp> apps = new ArrayList<>();
        for (String clientId : this.clientIdsByOwner.getOrDefault(owner, List.of())) {
            RegisteredClient client = this.clientRepository.findById(clientId);
            if (client != null) {
                apps.add(viewOf(client));
            }
        }
        apps.sort(Comparator.comparing(OwnedApp::createdAt, Comparator.reverseOrder())
                .thenComparing(OwnedApp::id));
        return List.copyOf(apps);
    }

    private static OwnedApp viewOf(RegisteredClient client) {
        List<String> redirectUris =
                client.getRedirectUris().stream().map(uri -> uri.toString()).toList();
        return new OwnedApp(
                client.getId(),
                client.getClientId(),
                client.getClientName(),
                client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.CLIENT_SECRET_BASIC),
                redirectUris,
                nullableInstant(client.getClientIdIssuedAt()));
    }

    /** clientIdIssuedAt 框架 build 保证非空（withId + clientId 即补默认），防御性兜底当前时刻。 */
    private static Instant nullableInstant(Instant instant) {
        return instant == null ? Instant.EPOCH : instant;
    }
}
