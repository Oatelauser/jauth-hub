package io.github.oatelauser.jauth.core.web;

import io.github.oatelauser.jauth.core.ratelimit.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 协议端点限流过滤器（SPEC §5 横切：X-RateLimit-* 三头）。
 *
 * <p><b>作用面</b>：token / introspection 两端点（任务口径）——高频机器面端点；authorize/consent 等人机
 * 端点不设限（表单频次由 CSRF 与会话天然约束）。
 *
 * <p><b>键取值（合并桶，GitHub 真实模型的落地近似）</b>：已认证主体名 → client_id 请求参数 → 远端地址。
 * token/introspection 的调用方是客户端（代表其背后用户行动），GitHub"按用户合并"在凭据呈现面即按调用方
 * 主体合并；个人应用（client 与用户一体）语义自然重合。
 *
 * <p><b>装配序位</b>：客户端认证过滤器之后、token/introspection 端点过滤器之前（框架端点过滤器挂在
 * AuthorizationFilter 之后，链内晚于客户端认证）——主体名可用，端点逻辑未跑。
 *
 * <p><b>超限响应</b>：429 + JSON {@code {"error":"rate_limited"}}（RFC 6750 的 JSON 错误形态；RFC 6749 的
 * form-encoded error 仅限 token 端点 4xx 家族，限流属两端点共用的传输层拒绝，JSON 是公共分母）。
 * 正常响应也带三头（X-RateLimit-Limit/Remaining/Reset）。
 *
 * @author oatelauser
 */
public final class RateLimitFilter extends OncePerRequestFilter {

    /** 超限响应体：协议端点 RFC 风格 error 字段（类注释的选择论证）。 */
    private static final byte[] RATE_LIMITED_BODY = "{\"error\":\"rate_limited\"}".getBytes(StandardCharsets.UTF_8);

    private final RateLimiter rateLimiter;

    private final String tokenPath;

    private final String introspectionPath;

    public RateLimitFilter(RateLimiter rateLimiter, String tokenPath, String introspectionPath) {
        this.rateLimiter = rateLimiter;
        this.tokenPath = tokenPath;
        this.introspectionPath = introspectionPath;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !tokenPath.equals(path) && !introspectionPath.equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        RateLimiter.Consumption consumption = rateLimiter.consume(bucketKey(request));
        response.setHeader("X-RateLimit-Limit", String.valueOf(consumption.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(consumption.remaining()));
        response.setHeader("X-RateLimit-Reset", String.valueOf(consumption.resetAfterSeconds()));
        if (!consumption.allowed()) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getOutputStream().write(RATE_LIMITED_BODY);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static String bucketKey(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && StringUtils.hasText(authentication.getName())) {
            return authentication.getName();
        }
        String clientId = request.getParameter("client_id");
        if (StringUtils.hasText(clientId)) {
            return clientId;
        }
        return request.getRemoteAddr();
    }
}
