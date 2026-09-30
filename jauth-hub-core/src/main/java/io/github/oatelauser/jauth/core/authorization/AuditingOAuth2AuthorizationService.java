package io.github.oatelauser.jauth.core.authorization;

import io.github.oatelauser.jauth.core.audit.AuditEvent;
import io.github.oatelauser.jauth.core.audit.AuditEventPublisher;
import io.github.oatelauser.jauth.core.audit.AuditEventType;
import io.github.oatelauser.jauth.core.token.TokenHash;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.util.Assert;

/**
 * 授权服务审计装饰（票 07：签发/刷新/撤销打审计点）：包住两模式的授权服务（JDBC 哈希手术版与 memory
 * 族谱包装版），事件源单点化。
 *
 * <p><b>委托面取 JDK 函数捕获</b>（构造参数的框架接口不存成员，方法引用拆成四个函数字段——与
 * DefaultClaimsContributor 同款取舍，SpotBugs EI_EXPOSE_REP2 洁净；也顺带钉死装饰面只经这四个入口）。
 *
 * <p><b>首签 vs 刷新的判定</b>：框架对同一授权行多次 save——/authorize 存 code（无 access token，
 * 不打点）、code 换令牌存首版 access+refresh（token.issued）、此后每次刷新轮转再 save（token.refreshed）。
 * 对象层面两形态不可无状态区分（刷新保留 code token 与全部 attributes），故以"本进程见过的
 * authorization id → 已打点的 access token 哈希"小状态对照：同 id 换了 access token 即刷新。
 * 状态有界（超限整清，类尾 ponytail 注）。
 *
 * <p><b>撤销的两条框架路径</b>（B7 实施修正——/revoke 并不走 remove）：/revoke 端点对授权做
 * save-置-INVALIDATED；selfservice 看板一键 revoke 走 remove。故撤销检测 = save 时 access/refresh 出现
 * INVALIDATED（按令牌哈希去重，一条令牌只记一行）∪ remove 调用本身。code token 的置 INVALIDATED 是
 * 兑换正常生命周期，<b>不在撤销检测之列</b>。
 *
 * <p><b>发令牌计数指标</b>（SPEC §8）：首签时递增 {@value #TOKENS_ISSUED_METRIC}，按 client 注册 id
 * 标签；MeterRegistry 缺席（宿主未引 micrometer 注册表）时零副作用（无操作计数器）。
 *
 * <p><b>内省不打审计</b>（票 07/P5）：本装饰不碰 findByToken 的读取路径——JDBC 版在该路径上的族谱
 * 探测（重放烧族）属安全熔断非生命周期事件，同样不打点。
 *
 * @author oatelauser
 */
public class AuditingOAuth2AuthorizationService implements OAuth2AuthorizationService {

    /** ponytail: 状态容量上限，超限整清（误记窗口 = 4096 活跃授权以上，v1 单实例下不可达）；升级路径 = 状态落库。 */
    private static final int MAX_TRACKED_AUTHORIZATIONS = 4096;

    /** 指标名（SPEC §8：发令牌计数，client 标签）。 */
    public static final String TOKENS_ISSUED_METRIC = "jauth.tokens.issued";

    private final Consumer<OAuth2Authorization> saveDelegate;

    private final Consumer<OAuth2Authorization> removeDelegate;

    private final Function<String, @Nullable OAuth2Authorization> findByIdDelegate;

    private final BiFunction<String, OAuth2TokenType, @Nullable OAuth2Authorization> findByTokenDelegate;

    /** 首签计数（client 标签 → 递增）；无 MeterRegistry 时为无操作。 */
    private final Consumer<String> issuedCounter;

    private final AuditEventPublisher auditPublisher;

    /** 见过的授权 id → 上次打点时的 access token 哈希（首签/刷新判定状态，类注释）。 */
    private final Map<String, String> seenIssuedAccessTokenHash = new HashMap<>();

    /** 已记过撤销的令牌哈希（save 置 INVALIDATED 与 remove 两路共用去重）。 */
    private final Set<String> revokedTokenHashes = new HashSet<>();

    public AuditingOAuth2AuthorizationService(
            OAuth2AuthorizationService delegate,
            AuditEventPublisher auditPublisher,
            @Nullable MeterRegistry meterRegistry) {
        Assert.notNull(delegate, "delegate cannot be null");
        Assert.notNull(auditPublisher, "auditPublisher cannot be null");
        this.saveDelegate = delegate::save;
        this.removeDelegate = delegate::remove;
        this.findByIdDelegate = delegate::findById;
        this.findByTokenDelegate = delegate::findByToken;
        this.auditPublisher = auditPublisher;
        this.issuedCounter = meterRegistry != null
                ? clientId -> meterRegistry
                        .counter(TOKENS_ISSUED_METRIC, Tags.of("client", clientId))
                        .increment()
                : clientId -> {};
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        this.saveDelegate.accept(authorization);
        boolean revoked = publishRevocations(authorization);
        OAuth2Authorization.Token<OAuth2AccessToken> accessToken = authorization.getAccessToken();
        if (accessToken == null || accessToken.isInvalidated()) {
            // /authorize 阶段只存 code 无令牌可记；/revoke 的 save 置 INVALIDATED 也不属于签发/刷新
            evictStateIfFull();
            return;
        }
        if (!revoked) {
            publishIssuedOrRefreshed(authorization, accessToken.getToken().getTokenValue());
        }
        evictStateIfFull();
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        this.removeDelegate.accept(authorization);
        OAuth2Authorization.Token<OAuth2AccessToken> accessToken = authorization.getAccessToken();
        if (accessToken != null) {
            publishRevocation(authorization, accessToken.getToken().getTokenValue(), "access_token");
        }
        OAuth2Authorization.Token<OAuth2RefreshToken> refreshToken = authorization.getRefreshToken();
        if (refreshToken != null) {
            publishRevocation(authorization, refreshToken.getToken().getTokenValue(), "refresh_token");
        }
    }

    @Override
    public @Nullable OAuth2Authorization findById(String id) {
        return this.findByIdDelegate.apply(id);
    }

    @Override
    public @Nullable OAuth2Authorization findByToken(String token, @Nullable OAuth2TokenType tokenType) {
        return this.findByTokenDelegate.apply(token, tokenType);
    }

    /**
     * save 路径的撤销检测：access/refresh 任一被置 INVALIDATED 且该令牌未记过撤销，即记 token.revoked
     * （每令牌一条，去重集保证）。
     *
     * @return true 表示本次 save 含撤销语义
     */
    private synchronized boolean publishRevocations(OAuth2Authorization authorization) {
        boolean revoked = false;
        OAuth2Authorization.Token<OAuth2AccessToken> accessToken = authorization.getAccessToken();
        if (accessToken != null && accessToken.isInvalidated()) {
            revoked |= publishRevocation(authorization, accessToken.getToken().getTokenValue(), "access_token");
        }
        OAuth2Authorization.Token<OAuth2RefreshToken> refreshToken = authorization.getRefreshToken();
        if (refreshToken != null && refreshToken.isInvalidated()) {
            revoked |= publishRevocation(authorization, refreshToken.getToken().getTokenValue(), "refresh_token");
        }
        return revoked;
    }

    /** 记一条撤销（令牌哈希去重）；已记过返回 false。 */
    private synchronized boolean publishRevocation(
            OAuth2Authorization authorization, String tokenValue, String tokenType) {
        if (!this.revokedTokenHashes.add(TokenHash.sha256Hex(tokenValue))) {
            return false;
        }
        this.auditPublisher.publish(AuditEvent.of(
                AuditEventType.TOKEN_REVOKED,
                authorization.getPrincipalName(),
                "authorization",
                authorization.getId(),
                "client=" + authorization.getRegisteredClientId() + ",token_type=" + tokenType));
        return true;
    }

    /** 首签/刷新判定与事件发布（类注释的状态对照法）。 */
    private synchronized void publishIssuedOrRefreshed(OAuth2Authorization authorization, String accessTokenValue) {
        String accessTokenHash = TokenHash.sha256Hex(accessTokenValue);
        String previousHash = this.seenIssuedAccessTokenHash.put(authorization.getId(), accessTokenHash);
        if (previousHash == null) {
            this.issuedCounter.accept(authorization.getRegisteredClientId());
            this.auditPublisher.publish(AuditEvent.of(
                    AuditEventType.TOKEN_ISSUED,
                    authorization.getPrincipalName(),
                    "authorization",
                    authorization.getId(),
                    "client=" + authorization.getRegisteredClientId()));
            return;
        }
        if (!previousHash.equals(accessTokenHash)) {
            this.auditPublisher.publish(AuditEvent.of(
                    AuditEventType.TOKEN_REFRESHED,
                    authorization.getPrincipalName(),
                    "authorization",
                    authorization.getId(),
                    "client=" + authorization.getRegisteredClientId()));
        }
        // 同哈希重复 save（无新令牌）：不打点
    }

    private synchronized void evictStateIfFull() {
        if (this.seenIssuedAccessTokenHash.size() > MAX_TRACKED_AUTHORIZATIONS
                || this.revokedTokenHashes.size() > MAX_TRACKED_AUTHORIZATIONS) {
            this.seenIssuedAccessTokenHash.clear();
            this.revokedTokenHashes.clear();
        }
    }
}
