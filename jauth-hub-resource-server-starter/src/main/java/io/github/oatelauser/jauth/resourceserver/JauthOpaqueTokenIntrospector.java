package io.github.oatelauser.jauth.resourceserver;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionAuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 内省端点直连实现：POST {@code token=} 表单 + Basic 机密客户端凭证（06 票），解析 RFC 7662 响应。
 *
 * <p>claims 照单全收（07 票内省富化：sub/username/scope/orgs 及端点返回的一切字段全部进 principal
 * attributes——业务侧细粒度鉴权的数据源）；唯 exp/iat/nbf 归一为 {@link Instant}（框架消费侧强转，见
 * {@link #introspect}）；scope 额外按 {@code authority-prefix} 策略映射为 authorities。
 *
 * <p>{@code active} 缺失或非 true 一律视为不活跃（RFC 7662：active 必填，缺省即假）——与"端点故障"的区分
 * 由异常类型承担：{@link InactiveOAuth2TokenException} 是端点的确定性判定（可负缓存），其余失败（网络、
 * 非 2xx、解析错）抛普通 {@link OAuth2IntrospectionException}（不缓存，见 {@link CachingIntrospector}）。
 *
 * @author oatelauser
 */
public class JauthOpaqueTokenIntrospector implements OpaqueTokenIntrospector {

    static final String CLAIM_ACTIVE = "active";

    static final String CLAIM_SCOPE = "scope";

    static final String CLAIM_SUB = "sub";

    static final String CLAIM_CLIENT_ID = "client_id";

    /**
     * 数值型时间 claim（RFC 7662 秒数）：框架 {@code OpaqueTokenAuthenticationProvider} 对 iat/exp 直接
     * 强转 {@link Instant}，Integer 原样透传会 ClassCastException（内省成功的 Bearer 请求全 500）。
     */
    private static final Set<String> EPOCH_SECOND_CLAIMS = Set.of("exp", "iat", "nbf");

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    private final String introspectionUri;

    private final String clientId;

    private final String clientSecret;

    private final String authorityPrefix;

    /**
     * EI_EXPOSE_REP2 定向豁免：RestClient 是 build 定型的无状态门面（构造后无可变面），持有引用不构成
     * 表示泄漏；这是 Spring 全家（如 RestOperations 注入）的通行形态。
     */
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2")
    public JauthOpaqueTokenIntrospector(
            RestClient restClient,
            String introspectionUri,
            String clientId,
            String clientSecret,
            String authorityPrefix) {
        this.restClient = restClient;
        this.introspectionUri = introspectionUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.authorityPrefix = authorityPrefix;
    }

    @Override
    public OAuth2AuthenticatedPrincipal introspect(String token) {
        Map<String, Object> claims = exchange(token);
        if (!Boolean.TRUE.equals(claims.get(CLAIM_ACTIVE))) {
            throw new InactiveOAuth2TokenException("Token is not active");
        }
        List<GrantedAuthority> authorities = mapScopesToAuthorities(claims.get(CLAIM_SCOPE));
        return new OAuth2IntrospectionAuthenticatedPrincipal(
                principalName(claims, token), normalizeTimeClaims(claims), authorities);
    }

    /**
     * exp/iat/nbf 归一为 Instant 再进 principal attributes（框架 OpaqueTokenAuthenticationProvider 对
     * iat/exp 强转 Instant）：数值按 RFC 7662 秒数解析；字符串形兼容（纯数字按秒，否则按 ISO-8601）；
     * 无法解析的丢弃——原样透传必然在框架侧 CCE，弃值好过 500。
     */
    private static Map<String, Object> normalizeTimeClaims(Map<String, Object> claims) {
        Map<String, Object> normalized = new LinkedHashMap<>(claims);
        for (String claim : EPOCH_SECOND_CLAIMS) {
            Object value = normalized.get(claim);
            if (value != null && !(value instanceof Instant)) {
                toInstant(value)
                        .ifPresentOrElse(instant -> normalized.put(claim, instant), () -> normalized.remove(claim));
            }
        }
        return normalized;
    }

    private static Optional<Instant> toInstant(Object value) {
        if (value instanceof Number number) {
            return Optional.of(Instant.ofEpochSecond(number.longValue()));
        }
        if (value instanceof String text) {
            try {
                return Optional.of(Instant.ofEpochSecond(Long.parseLong(text.trim())));
            } catch (NumberFormatException ignored) {
                try {
                    return Optional.of(Instant.parse(text.trim()));
                } catch (DateTimeParseException ignoredAgain) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    private Map<String, Object> exchange(String token) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", token);
        try {
            Map<String, Object> claims = this.restClient
                    .post()
                    .uri(this.introspectionUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .headers(headers -> headers.setBasicAuth(this.clientId, this.clientSecret, StandardCharsets.UTF_8))
                    .body(form)
                    .retrieve()
                    .body(JSON_MAP);
            return claims != null ? claims : Map.of();
        } catch (RestClientException | IllegalArgumentException ex) {
            throw new OAuth2IntrospectionException("Introspection endpoint call failed", ex);
        }
    }

    private List<GrantedAuthority> mapScopesToAuthorities(@Nullable Object scopeClaim) {
        // scope 为空格分隔字符串（RFC 7662）；连续空白容错（B3 曾见框架侧 whitespace scope 怪癖）
        if (!(scopeClaim instanceof String scopes) || scopes.isBlank()) {
            return List.of();
        }
        return Arrays.stream(scopes.split("\\s+"))
                .filter(Predicate.not(String::isEmpty))
                .<GrantedAuthority>map(scope -> new SimpleGrantedAuthority(this.authorityPrefix + scope))
                .toList();
    }

    private static String principalName(Map<String, Object> claims, String token) {
        // 用户令牌有 sub（jauth 用户 id）；client_credentials 令牌退 client_id；再退原始 token 串兜底
        Object sub = claims.get(CLAIM_SUB);
        if (sub != null) {
            return sub.toString();
        }
        Object clientIdClaim = claims.get(CLAIM_CLIENT_ID);
        return clientIdClaim != null ? clientIdClaim.toString() : token;
    }

    /**
     * 内省端点确定性判定 active=false（区别于端点故障，可被 {@link CachingIntrospector} 负缓存）。
     */
    static final class InactiveOAuth2TokenException extends OAuth2IntrospectionException {

        InactiveOAuth2TokenException(String message) {
            super(message);
        }
    }
}
