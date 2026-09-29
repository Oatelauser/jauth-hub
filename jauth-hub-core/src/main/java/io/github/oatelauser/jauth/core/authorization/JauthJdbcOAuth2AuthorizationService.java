package io.github.oatelauser.jauth.core.authorization;

import io.github.oatelauser.jauth.core.token.JdbcTokenFamilyService;
import io.github.oatelauser.jauth.core.token.TokenHash;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2DeviceCode;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.core.OAuth2UserCode;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * 框架 {@link JdbcOAuth2AuthorizationService} 的令牌哈希手术（SPEC §3 / 票 07 P1）+ RTR 族谱熔断（SPEC §6）：
 * access/refresh/authorization_code/oidc id_token/user_code/device_code 六个值列只存 {@link
 * TokenHash#sha256Hex(String)} 的结果，数据库永不见明文令牌。
 *
 * <p>实现方式：框架的 SELECT/INSERT/UPDATE SQL 与过滤器全部是 private static final，不可覆写、 不可注入列；但构造器暴露了 {@code
 * setAuthorizationParametersMapper} 公共扩展点。故不复制框架 33 列
 * SQL（升级时列漂移要人工同步），而是在参数映射层做值替换——写路径把六个令牌值参数换成哈希， 读路径按哈希查询，SQL 本体完全复用框架。
 *
 * <p><b>再哈希防护</b>：吊销/刷新轮转等路径会把从库读回的授权对象（令牌值已是哈希）再次 save。 哈希输出恰为 64
 * 位小写十六进制，而真实令牌（Base64URL/JWT）不会命中该形状，故值匹配 {@code [0-9a-f]{64}} 时视为已哈希直接放行，避免双重哈希烧坏行。
 *
 * <p><b>查找语义</b>：{@code state} 非凭据、原文存取；其余类型（含 tokenType 为 null 的内省/吊销入口， 其 OR 过滤器混合了原文 state
 * 与六个哈希列，无法整体复用）先哈希再查；null 类型退化为按类型 顺序探测，语义与框架单条 OR 查询等价。
 *
 * <p><b>RTR 族谱熔断</b>（B2）：save 路径把每次出现的 refresh token 哈希记入 jauth_token_family （一轮转一行，见 {@link
 * JdbcTokenFamilyService}）；findByToken 对 refresh 查找未命中时按历史哈希探测族谱， 命中即判定重放——烧断全族：删除该 user+client
 * 的全部授权行（access/refresh 一并失效，内省即 inactive）， 族谱行全标 BURNED，随后返回 null（框架报
 * invalid_grant）。<b>烧断后用户须重新登录授权</b>—— 这是重放（令牌被盗用的信号）的代价，属 SPEC §6 明确语义，非可降级行为。
 *
 * <p>必须在 Flyway 迁移完成后构造：框架构造函数读取表列元数据决定 TEXT 列的读写策略。 构造后 findByToken
 * 返回的授权对象中令牌值已回填为明文（框架内省/撤销端点的二次值比对需要，见 {@link
 * #withBackfilledPlaintextToken}——仅内存对象，不落库），数据库列恒为哈希；框架各消费路径（内省、吊销、刷新、
 * userinfo、OIDC logout）只用其元数据/claims（claims 存于 metadata 列），不回显值本身。
 *
 * @author oatelauser
 */
public class JauthJdbcOAuth2AuthorizationService extends JdbcOAuth2AuthorizationService {

    /** 框架参数列表长度（33 列），升级框架时的护栏：不匹配即快速失败。 */
    private static final int PARAMETER_COUNT = 33;

    /**
     * 六个令牌值参数在框架 COLUMN_NAMES 顺序中的下标（0 起）：authorization_code=7, access=11, oidc_id_token=17,
     * refresh=21, user_code=25, device_code=29。框架升级若变列序，由参数长度 护栏与哈希往返单测共同拦截。
     */
    private static final int[] TOKEN_VALUE_PARAMETER_INDEXES = {7, 11, 17, 21, 25, 29};

    private static final Pattern SHA256_HEX_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private static final OAuth2TokenType STATE_TOKEN_TYPE = new OAuth2TokenType(OAuth2ParameterNames.STATE);

    private static final OAuth2TokenType CODE_TOKEN_TYPE = new OAuth2TokenType(OAuth2ParameterNames.CODE);

    private static final OAuth2TokenType ID_TOKEN_TOKEN_TYPE = new OAuth2TokenType(OidcParameterNames.ID_TOKEN);

    private static final OAuth2TokenType USER_CODE_TOKEN_TYPE = new OAuth2TokenType(OAuth2ParameterNames.USER_CODE);

    private static final OAuth2TokenType DEVICE_CODE_TOKEN_TYPE = new OAuth2TokenType(OAuth2ParameterNames.DEVICE_CODE);

    /** 烧族第一步：删该 user+client 的全部授权行（先删后烧的顺序见 {@link #burnFamily} 注释）。 */
    private static final String DELETE_FAMILY_AUTHORIZATIONS =
            "DELETE FROM oauth2_authorization WHERE principal_name = ? AND registered_client_id =" + " ?";

    private final JdbcOperations jdbcOperations;

    private final JdbcTokenFamilyService tokenFamilyService;

    public JauthJdbcOAuth2AuthorizationService(
            JdbcOperations jdbcOperations,
            RegisteredClientRepository registeredClientRepository,
            JdbcTokenFamilyService tokenFamilyService) {
        super(jdbcOperations, registeredClientRepository);
        this.jdbcOperations = jdbcOperations;
        this.tokenFamilyService = tokenFamilyService;
        Function<OAuth2Authorization, List<SqlParameterValue>> frameworkMapper = getAuthorizationParametersMapper();
        setAuthorizationParametersMapper(authorization -> hashTokenValues(frameworkMapper.apply(authorization)));
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        super.save(authorization);
        recordRefreshTokenFamily(authorization);
    }

    /**
     * save 路径族谱记录：授权带 refresh token 即记入族谱（同哈希幂等跳过，见族谱存储）。 覆盖两条框架路径——首次签发（code 换令牌）与每次刷新轮转（同一授权行更新为新
     * refresh 值）。
     */
    private void recordRefreshTokenFamily(OAuth2Authorization authorization) {
        OAuth2Authorization.Token<OAuth2RefreshToken> refreshToken = authorization.getRefreshToken();
        if (refreshToken == null || !StringUtils.hasText(refreshToken.getToken().getTokenValue())) {
            return;
        }
        tokenFamilyService.recordRefreshToken(
                authorization.getPrincipalName(),
                authorization.getRegisteredClientId(),
                hashIfRaw(refreshToken.getToken().getTokenValue()));
    }

    @Override
    public @Nullable OAuth2Authorization findByToken(String token, @Nullable OAuth2TokenType tokenType) {
        Assert.hasText(token, "token cannot be empty");
        OAuth2Authorization found = doFindByToken(token, tokenType);
        if (found != null) {
            return withBackfilledPlaintextToken(found, token);
        }
        // refresh 未命中（或内省/吊销全类型未命中）→ 族谱探测：历史哈希命中即烧族（类注释 RTR 节）
        if (tokenType == null || OAuth2TokenType.REFRESH_TOKEN.equals(tokenType)) {
            burnFamilyIfReplayed(token);
        }
        return null;
    }

    /**
     * 命中后回填明文令牌值（框架值比对盲区）：内省/撤销等框架消费方拿到授权后会再以<b>明文值</b>调
     * {@code authorization.getToken(明文)} 做二次比对断言，而库里读回的令牌值是哈希，永不命中 → Assert 抛 →
     * /introspect 500、/revoke 400。故返回前把明文值回填进对应令牌对象（{@link OAuth2Authorization}
     * 不可变，builder 重建；token 元数据/claims 由 builder 自动继承）。回填<b>仅存在于返回对象，不落库</b>——
     * 再 save 时哈希手术对明文重新哈希，列值不变。state 路径原文存取，无哈希命中即原样返回。
     */
    private static OAuth2Authorization withBackfilledPlaintextToken(OAuth2Authorization authorization, String token) {
        OAuth2Authorization.Token<? extends OAuth2Token> stored = authorization.getToken(TokenHash.sha256Hex(token));
        if (stored == null) {
            return authorization;
        }
        return OAuth2Authorization.from(authorization)
                .token(withTokenValue(stored.getToken(), token))
                .build();
    }

    /** 同类令牌对象换 tokenValue（issued/expires/scopes/claims 全保留）；六类之外的自定义令牌形状不动，原样返回。 */
    private static OAuth2Token withTokenValue(OAuth2Token token, String tokenValue) {
        if (token instanceof OidcIdToken idToken) {
            return new OidcIdToken(tokenValue, idToken.getIssuedAt(), idToken.getExpiresAt(), idToken.getClaims());
        }
        if (token instanceof OAuth2AccessToken accessToken) {
            return new OAuth2AccessToken(
                    accessToken.getTokenType(),
                    tokenValue,
                    accessToken.getIssuedAt(),
                    accessToken.getExpiresAt(),
                    accessToken.getScopes());
        }
        if (token instanceof OAuth2RefreshToken refreshToken) {
            return new OAuth2RefreshToken(tokenValue, refreshToken.getIssuedAt(), refreshToken.getExpiresAt());
        }
        if (token instanceof OAuth2UserCode userCode) {
            return new OAuth2UserCode(tokenValue, userCode.getIssuedAt(), userCode.getExpiresAt());
        }
        if (token instanceof OAuth2DeviceCode deviceCode) {
            return new OAuth2DeviceCode(tokenValue, deviceCode.getIssuedAt(), deviceCode.getExpiresAt());
        }
        if (token instanceof OAuth2AuthorizationCode authorizationCode) {
            return new OAuth2AuthorizationCode(
                    tokenValue, authorizationCode.getIssuedAt(), authorizationCode.getExpiresAt());
        }
        return token;
    }

    /** 原 findByToken 主体（哈希查找语义不变，抽出以给族谱探测让出未命中分支）。 */
    private @Nullable OAuth2Authorization doFindByToken(String token, @Nullable OAuth2TokenType tokenType) {
        if (tokenType == null) {
            return findUnknownType(token);
        }
        if (OAuth2ParameterNames.STATE.equals(tokenType.getValue())) {
            return super.findByToken(token, tokenType);
        }
        return super.findByToken(TokenHash.sha256Hex(token), tokenType);
    }

    /** 未知类型入口（内省/吊销）：state 原文，其余六类哈希后按框架类型过滤器顺序探测。 框架单条 OR 查询无法复用（首参是原文 state、其余是哈希），退化查询语义等价。 */
    private @Nullable OAuth2Authorization findUnknownType(String token) {
        OAuth2Authorization hit = super.findByToken(token, STATE_TOKEN_TYPE);
        if (hit != null) {
            return hit;
        }
        String hashedToken = TokenHash.sha256Hex(token);
        for (OAuth2TokenType type : List.of(
                CODE_TOKEN_TYPE,
                OAuth2TokenType.ACCESS_TOKEN,
                ID_TOKEN_TOKEN_TYPE,
                OAuth2TokenType.REFRESH_TOKEN,
                USER_CODE_TOKEN_TYPE,
                DEVICE_CODE_TOKEN_TYPE)) {
            hit = super.findByToken(hashedToken, type);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /**
     * 重放探测与烧族：族谱命中历史哈希（含已烧族的重复重放，幂等）即删该 user+client 全部授权行、 族谱标
     * BURNED。顺序为先删授权后烧族谱——两步非事务，中断在任何一点，下次重放会重新走完剩余步骤 （自愈收敛）；反序会留下"族已 BURNED 但授权仍可用"的窗口。
     */
    private void burnFamilyIfReplayed(String token) {
        tokenFamilyService
                .findByRefreshTokenHash(hashIfRaw(token))
                .ifPresent(row -> burnFamily(row.principalName(), row.registeredClientId()));
    }

    private void burnFamily(String principalName, String registeredClientId) {
        jdbcOperations.update(DELETE_FAMILY_AUTHORIZATIONS, principalName, registeredClientId);
        tokenFamilyService.burnFamily(principalName, registeredClientId);
    }

    /** 值已是 64 位十六进制哈希形状（读回再 save 的授权对象）则原样使用，否则视为明文哈希之。 */
    private static String hashIfRaw(String tokenValue) {
        return isHashed(tokenValue) ? tokenValue : TokenHash.sha256Hex(tokenValue);
    }

    private List<SqlParameterValue> hashTokenValues(List<SqlParameterValue> parameters) {
        Assert.isTrue(
                parameters.size() == PARAMETER_COUNT,
                "Framework parameter layout changed: expected "
                        + PARAMETER_COUNT
                        + " parameters but got "
                        + parameters.size());
        List<SqlParameterValue> hashed = new ArrayList<>(parameters);
        for (int index : TOKEN_VALUE_PARAMETER_INDEXES) {
            SqlParameterValue parameter = parameters.get(index);
            Object value = parameter.getValue();
            if (value instanceof String tokenValue && StringUtils.hasText(tokenValue) && !isHashed(tokenValue)) {
                hashed.set(index, new SqlParameterValue(parameter.getSqlType(), TokenHash.sha256Hex(tokenValue)));
            }
        }
        return hashed;
    }

    private static boolean isHashed(String tokenValue) {
        return SHA256_HEX_PATTERN.matcher(tokenValue).matches();
    }
}
