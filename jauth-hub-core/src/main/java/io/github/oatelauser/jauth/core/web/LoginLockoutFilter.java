package io.github.oatelauser.jauth.core.web;

import io.github.oatelauser.jauth.core.ratelimit.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 登录失败锁定闸（SPEC §6：连错 5 次锁 15min，经 {@link RateLimiter} 实现）。
 *
 * <p>置于用户名口令认证过滤器<b>之前</b>：POST 登录且用户名处于锁定期 → 不再触达口令校验直接拒绝。
 * 拒绝形态选 401 家族而非 429（选择理由）：浏览器请求 302 回 /login?error——与口令错误完全同形，表单
 * 错误 UX 复用且防用户名枚举（响应形状不泄露"该用户名存在且已被锁"）；非浏览器请求 401 + JSON
 * {@code error=login_locked}（机器调用方需要可判别的失败码，401 是登录语义的正确状态位，429 会诱导
 * 调用方按限流退避重试、恰是爆破方想要的行为）。
 *
 * @author oatelauser
 */
public final class LoginLockoutFilter extends OncePerRequestFilter {

    /** 与框架 UsernamePasswordAuthenticationFilter 默认一致的用户名表单域。 */
    static final String USERNAME_PARAMETER = "username";

    private static final byte[] LOCKED_BODY =
            "{\"error\":\"login_locked\",\"error_description\":\"too many failed logins\"}"
                    .getBytes(StandardCharsets.UTF_8);

    private final RateLimiter rateLimiter;

    private final String loginPath;

    public LoginLockoutFilter(RateLimiter rateLimiter, String loginPath) {
        this.rateLimiter = rateLimiter;
        this.loginPath = loginPath;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && loginPath.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String username = request.getParameter(USERNAME_PARAMETER);
        if (StringUtils.hasText(username) && rateLimiter.isLoginLocked(username)) {
            reject(request, response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    /** 拒绝形态分支：浏览器同口令错误（302 /login?error），机器调用 401 + JSON 错误码。 */
    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (browserRequest(request)) {
            // 与框架登录失败处理器同形（?error 由登录页消费渲染通用错误文案）
            response.sendRedirect(request.getContextPath() + loginPath + "?error");
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getOutputStream().write(LOCKED_BODY);
    }

    private static boolean browserRequest(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("text/html");
    }
}
