package io.github.oatelauser.jauth.starter;

import io.github.oatelauser.jauth.core.token.TokenHash;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.util.Assert;

/**
 * PAT 内省叠加层的存储探针（表 jauth_pat，jdbc 模式专用；B5 遗留闭环：PAT 不在 oauth2_authorization，
 * 内省端点查不到）。
 *
 * <p><b>为什么在 starter 且直接走 JdbcTemplate</b>：PAT 域门面（PatService）在 selfservice 模块，而
 * starter 不可依赖 selfservice（依赖方向：selfservice → core；starter 引 selfservice 会把自助页强加给
 * 所有宿主）。本探针是叠加层的专用读径（按哈希命一行 + 回填 last_used），不重复 selfservice 的生命周期
 * 面；jauth_pat 表归 core Flyway（V2），属 SPEC 锁定的公共表结构。
 *
 * <p><b>PAT 专用 client 标识</b>：内省 active 响应的 client_id 框架取自授权行的 registeredClientId →
 * RegisteredClient 反查（provider 侧 Assert 非空）。构造时向活动仓库幂等播种一枚固定 id 的
 * {@value #PAT_CLIENT_ID} 伪客户端，PAT 合成授权行即指向它——伪客户端无 secret、无授权能力，仅作
 * client_id 呈现与 provider 断言满足。
 *
 * <p><b>last_used_at</b>：PAT 无刷新链路，"最近使用"由使用（内省/平台端点）回填（票 07 语义的 PAT 落地）。
 * <b>不打审计</b>（任务二选一的取舍）："内省不打审计"在 PAT 上同样成立且更必须——内省是资源服务器
 * 每请求（30s 缓存）的高频路径，pat.used 入审计表会以请求量级刷爆追加只写表；"最近使用"的观测需求
 * 已由 last_used_at 列承载，审计表回归生命周期事件本位。
 *
 * @author oatelauser
 */
final class PatIntrospectionSupport {

    /** PAT 伪客户端 id（registeredClientId 与 clientId 同值，固定字面量）。 */
    static final String PAT_CLIENT_ID = "jauth-pat";

    private final JdbcOperations jdbcOperations;

    private final RegisteredClientRepository registeredClientRepository;

    private final UserRepository userRepository;

    private final Clock clock;

    /** PAT 伪客户端（合成授权行的 client 指向；类注释），构造期幂等播种进活动仓库。 */
    private final RegisteredClient patClient = RegisteredClient.withId(PAT_CLIENT_ID)
            .clientId(PAT_CLIENT_ID)
            .clientIdIssuedAt(Instant.EPOCH)
            .clientName("Personal Access Token (virtual)")
            // 框架 build 要求 grant 非空：给一枚自造 URN——无 secret、无认证方式，任何真实授权流都无法
            // 以它发起，仅满足 RegisteredClient 形状约束（内省 provider 只读它的 clientId）
            .authorizationGrantType(new AuthorizationGrantType("urn:jauth-hub:pat"))
            .tokenSettings(TokenSettings.builder()
                    .accessTokenFormat(OAuth2TokenFormat.REFERENCE)
                    .build())
            .build();

    PatIntrospectionSupport(
            JdbcOperations jdbcOperations,
            RegisteredClientRepository registeredClientRepository,
            UserRepository userRepository,
            Clock clock) {
        Assert.notNull(jdbcOperations, "jdbcOperations cannot be null");
        Assert.notNull(registeredClientRepository, "registeredClientRepository cannot be null");
        Assert.notNull(userRepository, "userRepository cannot be null");
        Assert.notNull(clock, "clock cannot be null");
        this.jdbcOperations = jdbcOperations;
        this.registeredClientRepository = registeredClientRepository;
        this.userRepository = userRepository;
        this.clock = clock;
        seedPatClient();
    }

    /**
     * 按明文令牌值探一行 ACTIVE 且未过期的 PAT。
     *
     * @param tokenValue 令牌明文（jpat_ 前缀）
     * @return 命中行；未命中/已吊销/已过期为空
     */
    Optional<PatHit> findByTokenValue(String tokenValue) {
        List<PatHit> hits = jdbcOperations.query(
                "SELECT id, user_id, scopes, created_at, expires_at FROM jauth_pat"
                        + " WHERE token_sha256 = ? AND status = 'ACTIVE' AND expires_at > ?",
                (rs, rowNum) -> new PatHit(
                        rs.getString("id"),
                        rs.getString("user_id"),
                        splitScopes(rs.getString("scopes")),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant()),
                TokenHash.sha256Hex(tokenValue),
                Timestamp.from(clock.instant()));
        return hits.stream().findFirst();
    }

    /** 回填最近使用时刻（叠加层唯一写路径）。 */
    void touchLastUsed(String patId) {
        jdbcOperations.update(
                "UPDATE jauth_pat SET last_used_at = ? WHERE id = ?", Timestamp.from(clock.instant()), patId);
    }

    /**
     * 合成 PAT 的授权对象（框架内省 provider 的消费形状）：access token 值为明文（仅内存对象，不落库），
     * claims 携带 sub/username/scope（与 opaque 定制器的键同源），client 指向伪客户端。
     */
    OAuth2Authorization toAuthorization(String tokenValue, PatHit hit) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", hit.userId());
        String username = resolveUsername(hit.userId());
        if (username != null) {
            claims.put("username", username);
        }
        claims.put("scope", String.join(" ", hit.scopes()));
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, tokenValue, hit.createdAt(), hit.expiresAt(), hit.scopes());
        return OAuth2Authorization.withRegisteredClient(patClient)
                .id("pat-" + hit.patId())
                .principalName(hit.userId())
                // 框架 Builder 要求授权类型非空：自造 URN（与伪客户端同款理由，见 patClient 注释）
                .authorizationGrantType(new AuthorizationGrantType("urn:jauth-hub:pat"))
                .token(accessToken, metadata -> metadata.put(OAuth2Authorization.Token.CLAIMS_METADATA_NAME, claims))
                .build();
    }

    private void seedPatClient() {
        if (registeredClientRepository.findByClientId(PAT_CLIENT_ID) == null) {
            registeredClientRepository.save(patClient);
        }
    }

    /** 用户 id → 登录名（叠加层与 /me 适配共用）。 */
    @Nullable
    String resolveUsername(String userId) {
        JauthUser user = userRepository.findById(userId);
        return user != null ? user.username() : null;
    }

    private static Set<String> splitScopes(String joined) {
        if (joined == null || joined.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(joined.trim().split("\\s+")).collect(Collectors.toUnmodifiableSet());
    }

    /** PAT 命中行（叠加层的内部投影）。 */
    record PatHit(String patId, String userId, Set<String> scopes, Instant createdAt, Instant expiresAt) {}
}
