package io.github.oatelauser.jauth.core.client;

import io.github.oatelauser.jauth.core.util.UuidV7;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * 种子客户端播种器：把 {@link ClientSeedProperties} 中的定义 upsert 进传入的仓库。
 *
 * <p>幂等语义（SPEC §2 "properties 播种不是第二真源"）：client_id 已存在即整条跳过—— 不覆盖、不报错，重复执行安全。对内存/JDBC 两类仓库通用（只依赖
 * {@link RegisteredClientRepository} 接口）。
 *
 * <p>播种默认值：全部客户端强制 PKCE（SPEC §1 协议决议）；有 secret 走 client_secret_basic + client_secret_post 认证，无
 * secret 视为公开客户端（none）。
 *
 * @author oatelauser
 */
public class ClientSeeder {

    /** 构造时快照种子清单：播种定义固定，不受后续配置对象变更影响。 */
    private final List<ClientSeedProperties.ClientSeed> seeds;

    private final PasswordEncoder passwordEncoder;

    public ClientSeeder(ClientSeedProperties properties, PasswordEncoder passwordEncoder) {
        Assert.notNull(properties, "properties cannot be null");
        Assert.notNull(passwordEncoder, "passwordEncoder cannot be null");
        this.seeds = List.copyOf(properties.getClients());
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 执行播种（幂等）。
     *
     * @param repository 活动的客户端仓库（内存或 JDBC）
     */
    public void seed(RegisteredClientRepository repository) {
        Assert.notNull(repository, "repository cannot be null");
        for (ClientSeedProperties.ClientSeed seed : this.seeds) {
            if (repository.findByClientId(seed.getClientId()) != null) {
                continue;
            }
            repository.save(toRegisteredClient(seed));
        }
    }

    private RegisteredClient toRegisteredClient(ClientSeedProperties.ClientSeed seed) {
        validate(seed);
        RegisteredClient.Builder builder = RegisteredClient.withId(
                        UuidV7.generate().toString())
                .clientId(seed.getClientId())
                .clientIdIssuedAt(Instant.now())
                .clientName(seed.getClientName())
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(seed.isRequireAuthorizationConsent())
                        .build())
                // opaque 正典（SPEC §1）+ 令牌生命周期（SPEC §6 策略表默认，per-client 可覆盖）：
                // access 2h / refresh 30d / 刷新即轮转（RTR——重放检测与整族熔断依赖轮转链，框架默认
                // reuse(true) 下旧 refresh 永远有效，SPEC 承诺的熔断语义不会发生）
                .tokenSettings(TokenSettings.builder()
                        .accessTokenFormat(OAuth2TokenFormat.REFERENCE)
                        .accessTokenTimeToLive(Duration.ofHours(2))
                        .refreshTokenTimeToLive(Duration.ofDays(30))
                        .reuseRefreshTokens(false)
                        .build());
        if (StringUtils.hasText(seed.getClientSecret())) {
            builder.clientSecret(this.passwordEncoder.encode(seed.getClientSecret()))
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST);
        } else {
            builder.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
        }
        seed.getGrantTypes()
                .forEach(grantType -> builder.authorizationGrantType(new AuthorizationGrantType(grantType)));
        seed.getRedirectUris().forEach(builder::redirectUri);
        seed.getPostLogoutRedirectUris().forEach(builder::postLogoutRedirectUri);
        seed.getScopes().forEach(builder::scope);
        return builder.build();
    }

    private static void validate(ClientSeedProperties.ClientSeed seed) {
        Assert.hasText(seed.getClientId(), "jauth-hub.clients[].client-id cannot be empty");
        Assert.hasText(seed.getClientName(), "jauth-hub.clients[].client-name cannot be empty");
        Assert.isTrue(
                !CollectionUtils.isEmpty(seed.getGrantTypes()),
                "jauth-hub.clients[" + seed.getClientId() + "].grant-types cannot be empty");
        Assert.isTrue(
                !CollectionUtils.isEmpty(seed.getRedirectUris()),
                "jauth-hub.clients[" + seed.getClientId() + "].redirect-uris cannot be empty");
        Assert.isTrue(
                !CollectionUtils.isEmpty(seed.getScopes()),
                "jauth-hub.clients[" + seed.getClientId() + "].scopes cannot be empty");
    }
}
