package io.github.oatelauser.jauth.core.web;

import io.github.oatelauser.jauth.core.ratelimit.RateLimiter;
import io.github.oatelauser.jauth.core.response.JauthErrorCode;
import io.github.oatelauser.jauth.core.response.JauthException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * JSON 登录桥的认证编舞（v1.4 B2）：锁门 → authenticate → 会话动作 → 落点解析，逐项对齐框架
 * {@code UsernamePasswordAuthenticationFilter} 的成功路径语义（{@code AbstractAuthenticationProcessingFilter}
 * 的既定次序：会话策略先行、再置上下文、再持久化），供 {@link LoginApiController} 之外亦可脱离 MVC 单测。
 *
 * <p><b>为何不落地成过滤器（改 LoginLockoutFilter）而复刻在控制器侧</b>：锁定闸按请求形态钉死——
 * {@code LoginLockoutFilter#shouldNotFilter} 只认 {@code POST 且 path == /login}、用户名从表单参数读，
 * 不覆盖 JSON 面；JSON 桥在编舞入口做同语义锁门（同一 {@link RateLimiter} bean、同一用户名键），
 * 锁定期内 fail-closed 不触达 {@code authenticate}，与表单闸完全同口径（SPEC §6）。
 *
 * <p><b>事件源（审计/限流桥的输入）不做人工发布</b>：注入的 {@link AuthenticationManager}（starter 装配为
 * 无 parent 的 ProviderManager，显式挂上下文感知的 {@code DefaultAuthenticationEventPublisher}——照
 * {@code AuthenticationConfiguration} 的同款装配，javap 验证）在 authenticate 成功/失败时自动发布
 * {@code AuthenticationSuccessEvent}/失败事件进应用上下文，{@code SecurityEventAuditBridge}（@EventListener）
 * 与表单过滤器同源接收；测试以审计落库与计数位移钉死该结论。
 *
 * <p><b>失败同形拒绝</b>：口令错/用户不存在/已停用（及会话策略异常）一律 A0520——不区分失败原因，
 * 防用户名枚举，与表单登录 {@code ?error} 的单一形态对齐。成功不打 sudo 强认证点（密码 ≠ 强认证，
 * 仅 passkey 成功打点，见桥类注释）。
 *
 * @author oatelauser
 */
public final class JsonLoginService {

    /** 无 saved request 时的落点：与 SavedRequestAwareAuthenticationSuccessHandler 默认同形（/ 上的落点归部署方路由）。 */
    static final String DEFAULT_REDIRECT_URL = "/";

    private final RateLimiter rateLimiter;

    private final AuthenticationManager authenticationManager;

    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;

    private final SecurityContextRepository securityContextRepository;

    private final RequestCache requestCache;

    public JsonLoginService(RateLimiter rateLimiter, AuthenticationManager authenticationManager) {
        this(
                rateLimiter,
                authenticationManager,
                // 框架 formLogin 的默认组合复刻（SessionManagementConfigurer 装配形）：Composite[会话固定防护
                // changeSessionId + CsrfAuthenticationStrategy 登录后重发 CSRF token（Xor 处理器默认，旧 token
                // 随会话固定作废——headless 皮照 B1 契约取新 token 续用）]；上下文持久化 = 请求属性 +
                // HttpSession 双写（DelegatingSecurityContextRepository 过滤器默认）
                new CompositeSessionAuthenticationStrategy(List.of(
                        new ChangeSessionIdAuthenticationStrategy(),
                        new CsrfAuthenticationStrategy(new HttpSessionCsrfTokenRepository()))),
                new DelegatingSecurityContextRepository(
                        new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository()),
                new HttpSessionRequestCache());
    }

    JsonLoginService(
            RateLimiter rateLimiter,
            AuthenticationManager authenticationManager,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            SecurityContextRepository securityContextRepository,
            RequestCache requestCache) {
        Assert.notNull(rateLimiter, "rateLimiter cannot be null");
        Assert.notNull(authenticationManager, "authenticationManager cannot be null");
        Assert.notNull(sessionAuthenticationStrategy, "sessionAuthenticationStrategy cannot be null");
        Assert.notNull(securityContextRepository, "securityContextRepository cannot be null");
        Assert.notNull(requestCache, "requestCache cannot be null");
        this.rateLimiter = rateLimiter;
        this.authenticationManager = authenticationManager;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.securityContextRepository = securityContextRepository;
        this.requestCache = requestCache;
    }

    /**
     * JSON 登录：锁门 → 认证 → 会话编舞，返回登录落点 URL。
     *
     * @param username 登录用户名（空值照走认证，落同形失败）
     * @param password 登录口令
     * @param request 当前请求（会话动作与 saved request 载体）
     * @param response 当前响应
     * @return 登录落点：RequestCache 中 saved request 的 URL（登录入口点 302 前保存的 /oauth2/authorize?...），
     *         无则 {@code /}
     * @throws JauthException A0521 锁定期内（先于口令校验）；A0520 认证失败（含会话策略异常）
     */
    public String login(String username, String password, HttpServletRequest request, HttpServletResponse response) {
        // 锁门先于口令校验：空白用户名与表单闸同判（不查锁、走认证落同形失败，防键控口令探测）
        if (StringUtils.hasText(username) && this.rateLimiter.isLoginLocked(username)) {
            throw new JauthException(JauthErrorCode.A0521);
        }
        Authentication authentication;
        try {
            authentication = this.authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(username, password));
            // 会话策略（Composite）：changeSessionId 会话固定防护 + 登录后重发 CSRF token（框架 formLogin 同款）
            this.sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
        } catch (AuthenticationException ex) {
            throw new JauthException(JauthErrorCode.A0520);
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        this.securityContextRepository.saveContext(context, request, response);
        return resolveRedirectUrl(request, response);
    }

    /** 落点解析：saved request 优先，无则 /（SavedRequestAwareAuthenticationSuccessHandler 默认同形）。 */
    private String resolveRedirectUrl(HttpServletRequest request, HttpServletResponse response) {
        SavedRequest savedRequest = this.requestCache.getRequest(request, response);
        return savedRequest != null ? savedRequest.getRedirectUrl() : DEFAULT_REDIRECT_URL;
    }
}
