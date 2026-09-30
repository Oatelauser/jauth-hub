package io.github.oatelauser.jauth.core.audit;

import io.github.oatelauser.jauth.core.ratelimit.RateLimiter;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.util.Assert;

/**
 * Spring Security 认证事件 → 审计/限流的接线桥（登录成功/失败两个事件源，票 07）。
 *
 * <p><b>只认表单登录</b>：过滤 {@link UsernamePasswordAuthenticationToken} 的认证请求——OAuth2 客户端
 * 认证（token 端点 basic 凭证）也走同一事件总线，混入会污染按用户名键控的登录锁定与 login.* 审计。
 *
 * <p><b>失败即限流登记</b>（SPEC §6：登录防爆破走限流器）：失败计数与审计同一事件源，两动作原子同现；
 * 成功清零。actor_user_id 经 {@link UserRepository} 反查（UserDetails 主体形状归宿主，用户名是唯一稳
 * 定键）。
 *
 * <p>由 starter 注册为 bean（core 无组件扫描）；memory/jdbc 两模式通用。
 *
 * @author oatelauser
 */
public class SecurityEventAuditBridge {

    private final AuditEventPublisher auditPublisher;

    private final RateLimiter rateLimiter;

    private final UserRepository userRepository;

    public SecurityEventAuditBridge(
            AuditEventPublisher auditPublisher, RateLimiter rateLimiter, UserRepository userRepository) {
        Assert.notNull(auditPublisher, "auditPublisher cannot be null");
        Assert.notNull(rateLimiter, "rateLimiter cannot be null");
        Assert.notNull(userRepository, "userRepository cannot be null");
        this.auditPublisher = auditPublisher;
        this.rateLimiter = rateLimiter;
        this.userRepository = userRepository;
    }

    @EventListener
    public void onLoginSuccess(AuthenticationSuccessEvent event) {
        Authentication authentication = event.getAuthentication();
        if (!isFormLogin(authentication)) {
            return;
        }
        rateLimiter.onLoginSuccess(authentication.getName());
        auditPublisher.publish(AuditEvent.of(
                AuditEventType.LOGIN_SUCCESS,
                resolveUserId(authentication.getName()),
                "user",
                authentication.getName(),
                null));
    }

    @EventListener
    public void onLoginFailure(AbstractAuthenticationFailureEvent event) {
        Authentication authentication = event.getAuthentication();
        if (!isFormLogin(authentication)) {
            return;
        }
        rateLimiter.onLoginFailure(authentication.getName());
        auditPublisher.publish(AuditEvent.of(
                AuditEventType.LOGIN_FAILED,
                resolveUserId(authentication.getName()),
                "user",
                authentication.getName(),
                "cause=" + event.getException().getClass().getSimpleName()));
    }

    private static boolean isFormLogin(Authentication authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication.getClass());
    }

    private @Nullable String resolveUserId(String username) {
        JauthUser user = userRepository.findByUsername(username);
        return user != null ? user.id() : null;
    }
}
