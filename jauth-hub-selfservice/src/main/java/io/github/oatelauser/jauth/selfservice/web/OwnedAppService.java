package io.github.oatelauser.jauth.selfservice.web;

import io.github.oatelauser.jauth.core.client.ClientOwner;
import io.github.oatelauser.jauth.core.scope.ScopeCatalog;
import io.github.oatelauser.jauth.core.scope.ScopeDefinition;
import io.github.oatelauser.jauth.core.util.UuidV7;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.util.Assert;

/**
 * 个人应用自助注册域门面（SPEC §3"个人应用免安装审批"：owner_user_id 直属，无安装/ceiling 步）。
 *
 * <p><b>构造惯例照 core ClientSeeder</b>：UUIDv7 主键、对外 client_id 随机（{@code app_} 前缀）、PKCE 强制、
 * SPEC §6 TTL（opaque access 2h / refresh 30d / 刷新即轮转）；机密应用随机 secret 经框架 PasswordEncoder 编码
 * 落库、授权类型 authorization_code + refresh_token；公开应用无 secret、认证方式 none、<b>不发 refresh token</b>
 * （SPEC §6 框架防线）。allowed scopes = {@link ScopeCatalog} 目录全集——个人应用无 ceiling，封顶由 consent 页
 * 把关（故 requireAuthorizationConsent=true：个人应用也可能被其他用户授权，不得静默跳过确认）。
 *
 * <p><b>secret 明文纪律</b>（对齐 {@link io.github.oatelauser.jauth.selfservice.pat.PatService} 展示惯例）：只在
 * {@link #register} 返回值出现一次，列表与任何查询面只有编码后的存储值（不可逆）。
 *
 * <p><b>双实现</b>：jdbc = {@link JdbcOwnedAppService}（client+owner 两条语句包事务，B10 滑账①收口）；memory =
 * {@link InMemoryOwnedAppService}（框架内存仓库 + owner 登记表）。memory 语义：注册记录重启即失，与框架
 * InMemoryRegisteredClientRepository 同寿命。
 *
 * @author oatelauser
 */
public abstract class OwnedAppService {

    /** 应用名上限（展示面截齐；列宽 varchar(200) 余量充足）。 */
    static final int NAME_MAX_LENGTH = 100;

    /** 对外 client_id 可识别头（区分种子/宿主配置的 client）。 */
    static final String CLIENT_ID_HEADER = "app_";

    /** redirect 白名单上限：redirect_uris 列 varchar(1000)，先于列宽拒绝恶意超填。 */
    static final int MAX_REDIRECT_URIS = 20;

    /** 单条 redirect URI 上限（列宽 guard 的一半即可，正常回调地址远短于此）。 */
    static final int MAX_REDIRECT_URI_LENGTH = 500;

    private static final int CLIENT_ID_ENTROPY_BYTES = 12;

    private static final int SECRET_ENTROPY_BYTES = 32;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final PasswordEncoder passwordEncoder;

    private final ScopeCatalog scopeCatalog;

    private final Clock clock;

    protected OwnedAppService(PasswordEncoder passwordEncoder, ScopeCatalog scopeCatalog, Clock clock) {
        this.passwordEncoder = passwordEncoder;
        this.scopeCatalog = scopeCatalog;
        this.clock = clock;
    }

    /**
     * 注册一枚个人应用：构造 RegisteredClient（惯例见类注释）并交由 {@link #persist} 双写落位。
     *
     * @param userId 归属用户 id（jauth_user.id）
     * @param name 应用名（非空，≤100 字符）
     * @param redirectUris 精确回调白名单（非空，元素须为 http/https 绝对 URL 无 fragment）
     * @param confidential true = 机密应用（随机 secret + refresh token）；false = 公开应用
     * @return 应用视图 + 仅此一次的明文 secret（公开应用为 null）
     */
    public Registration register(String userId, String name, Set<String> redirectUris, boolean confidential) {
        Assert.hasText(userId, "userId cannot be empty");
        Assert.hasText(name, "name cannot be empty");
        Assert.isTrue(name.trim().length() <= NAME_MAX_LENGTH, "name exceeds " + NAME_MAX_LENGTH + " chars");
        Assert.notEmpty(redirectUris, "redirectUris cannot be empty");
        redirectUris.forEach(OwnedAppService::requireValidRedirectUri);

        String rawSecret = confidential ? randomUrlSafe(SECRET_ENTROPY_BYTES) : null;
        RegisteredClient client = buildClient(name.trim(), redirectUris, confidential, rawSecret);
        persist(client, ClientOwner.ofUser(userId));
        return new Registration(
                new OwnedApp(
                        client.getId(),
                        client.getClientId(),
                        client.getClientName(),
                        confidential,
                        List.copyOf(redirectUris),
                        client.getClientIdIssuedAt()),
                rawSecret);
    }

    /**
     * 列出用户的个人应用（owner_user_id = userId，平台内置与 org 应用不在内）。
     *
     * @param userId 用户 id
     * @return 应用列表（新注册在前），机密应用的 secret 不回显
     */
    public abstract List<OwnedApp> list(String userId);

    /**
     * 双写落位（client 本体 + owner 归属）：jdbc 实现包事务，memory 实现为框架 save + owner 登记表 put。
     *
     * @param client 已构造完成的客户端
     * @param owner 个人归属（ofUser(userId)）
     */
    protected abstract void persist(RegisteredClient client, ClientOwner owner);

    /**
     * 解析注册表单的 redirect URIs 多行文本（换行分隔，空白行忽略）为已校验列表。
     *
     * <p>校验规则（信任边界，注册面唯一真源）：http/https 绝对 URL、无 fragment（OAuth 2.1 禁止）、条数 ≤
     * {@value #MAX_REDIRECT_URIS}、单条 ≤ {@value #MAX_REDIRECT_URI_LENGTH} 字符——框架授权时精确匹配，通配
     * 无意义故直接不收。
     *
     * @param raw 多行文本（null 视为空）
     * @return 校验通过的 URI 列表（空输入返回空列表，由调用方按"缺失"拒绝）
     * @throws IllegalArgumentException 含非法 URI 时
     */
    public static List<String> parseRedirectUris(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> uris = new ArrayList<>();
        for (String line : raw.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            requireValidRedirectUri(trimmed);
            uris.add(trimmed);
        }
        Assert.isTrue(uris.size() <= MAX_REDIRECT_URIS, "redirect URIs exceed " + MAX_REDIRECT_URIS);
        return List.copyOf(uris);
    }

    private RegisteredClient buildClient(
            String name, Set<String> redirectUris, boolean confidential, @Nullable String rawSecret) {
        RegisteredClient.Builder builder = RegisteredClient.withId(
                        UuidV7.generate().toString())
                .clientId(CLIENT_ID_HEADER + randomUrlSafe(CLIENT_ID_ENTROPY_BYTES))
                .clientIdIssuedAt(this.clock.instant())
                .clientName(name)
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(true)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenFormat(OAuth2TokenFormat.REFERENCE)
                        .accessTokenTimeToLive(Duration.ofHours(2))
                        .refreshTokenTimeToLive(Duration.ofDays(30))
                        .reuseRefreshTokens(false)
                        .build())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE);
        if (confidential) {
            builder.clientSecret(this.passwordEncoder.encode(rawSecret))
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                    .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN);
        } else {
            builder.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
        }
        redirectUris.forEach(builder::redirectUri);
        for (ScopeDefinition definition : this.scopeCatalog.all()) {
            builder.scope(definition.name());
        }
        return builder.build();
    }

    private static void requireValidRedirectUri(String candidate) {
        Assert.isTrue(candidate.length() <= MAX_REDIRECT_URI_LENGTH, "redirect URI exceeds max length: " + candidate);
        URI uri;
        try {
            uri = URI.create(candidate);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid redirect URI: " + candidate, ex);
        }
        String scheme = uri.getScheme();
        Assert.isTrue(
                uri.isAbsolute() && ("http".equals(scheme) || "https".equals(scheme)) && uri.getFragment() == null,
                "redirect URI must be an exact http(s) URL without fragment: " + candidate);
    }

    private static String randomUrlSafe(int entropyBytes) {
        byte[] entropy = new byte[entropyBytes];
        SECURE_RANDOM.nextBytes(entropy);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    }

    /** 个人应用列表行/注册结果视图：永不携带 secret（存储侧只有不可逆编码值）。 */
    public record OwnedApp(
            String id,
            String clientId,
            String name,
            boolean confidential,
            List<String> redirectUris,
            Instant createdAt) {

        /** redirectUris 防御性拷贝（SpotBugs EI_EXPOSE_REP：出入均不可变）。 */
        public OwnedApp {
            redirectUris = redirectUris == null ? List.of() : List.copyOf(redirectUris);
        }
    }

    /**
     * 注册结果：应用视图 + 仅此一次的明文 secret。
     *
     * <p>明文唯一出现点——消费方即写进注册响应，不做任何留存（对齐 PatIssuance 纪律）。
     */
    public record Registration(OwnedApp app, @Nullable String plaintextSecret) {}
}
