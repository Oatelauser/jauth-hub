package io.github.oatelauser.jauth.core.audit;

import io.github.oatelauser.jauth.core.passkey.JauthUserEntityRepository;
import io.github.oatelauser.jauth.core.ratelimit.RateLimiter;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import java.time.Clock;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthentication;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthenticationRequestToken;
import org.springframework.util.Assert;

/**
 * Spring Security 认证事件 → 审计/限流的接线桥（登录成功/失败两个事件源，票 07；v1.2 C2 增收 passkey）。
 *
 * <p><b>只认表单登录与 passkey 登录</b>：过滤 {@link UsernamePasswordAuthenticationToken} 与 WebAuthn 两形状
 * （成功 {@link WebAuthnAuthentication} / 失败 {@link WebAuthnAuthenticationRequestToken}）——OAuth2 客户端
 * 认证（token 端点 basic 凭证）也走同一事件总线，混入会污染按用户名键控的登录锁定与 login.* 审计。
 *
 * <p><b>失败即限流登记</b>（SPEC §6：登录防爆破走限流器）：失败计数与审计同一事件源，两动作原子同现；
 * 成功清零。actor_user_id 经 {@link UserRepository} 反查（UserDetails 主体形状归宿主，用户名是唯一稳
 * 定键）。
 *
 * <p><b>passkey 限流只登记可解析用户名</b>（C2 决议）：密码锁定防的是在线口令猜测，passkey 断言验签不是
 * 猜测预言机；usernameless 断言（userHandle 缺失或不指向 jauth 用户）无从按用户名键控，不造假桶。成功
 * 事件主体即用户实体（getName() = 登录名），照常清锁；失败事件经 userHandle 反查用户池，查得到才计数，
 * detail 不回记原始 userHandle——未验签的断言字段是攻击者可控输入，进审计徒占列。
 *
 * <p><b>passkey 成功即强认证打点</b>（v1.2 C3，sudo 依赖）：passkey 成功是"最近一次强认证"的唯一服务器侧
 * 汇聚点——此处落 {@code updateStrongAuthAt}（表单登录不打点：密码 ≠ 强认证）。打点与审计/限流同源同现，
 * sudo 判官（user 域 {@code SudoGate}）只读该列。
 *
 * <p>由 starter 注册为 bean（core 无组件扫描）；memory/jdbc 两模式通用。
 *
 * @author oatelauser
 */
public class SecurityEventAuditBridge {

    private static final String DETAIL_FACTOR_WEBAUTHN = "factor=webauthn";

    private final AuditEventPublisher auditPublisher;

    private final RateLimiter rateLimiter;

    private final UserRepository userRepository;

    private final Clock clock;

    public SecurityEventAuditBridge(
            AuditEventPublisher auditPublisher, RateLimiter rateLimiter, UserRepository userRepository, Clock clock) {
        Assert.notNull(auditPublisher, "auditPublisher cannot be null");
        Assert.notNull(rateLimiter, "rateLimiter cannot be null");
        Assert.notNull(userRepository, "userRepository cannot be null");
        Assert.notNull(clock, "clock cannot be null");
        this.auditPublisher = auditPublisher;
        this.rateLimiter = rateLimiter;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @EventListener
    public void onLoginSuccess(AuthenticationSuccessEvent event) {
        Authentication authentication = event.getAuthentication();
        if (isFormLogin(authentication)) {
            rateLimiter.onLoginSuccess(authentication.getName());
            auditPublisher.publish(AuditEvent.of(
                    AuditEventType.LOGIN_SUCCESS,
                    resolveUserId(authentication.getName()),
                    "user",
                    authentication.getName(),
                    null));
            return;
        }
        if (isPasskeyLogin(authentication)) {
            // WebAuthnAuthentication 的 principal = 用户实体，getName() = 登录名
            String username = authentication.getName();
            rateLimiter.onLoginSuccess(username);
            JauthUser user = this.userRepository.findByUsername(username);
            if (user != null) {
                // 强认证打点（sudo 位）：passkey 成功是唯一汇聚点，表单成功不打点（类注释）
                this.userRepository.updateStrongAuthAt(user.id(), this.clock.instant());
            }
            auditPublisher.publish(AuditEvent.of(
                    AuditEventType.LOGIN_SUCCESS,
                    user == null ? null : user.id(),
                    "user",
                    username,
                    DETAIL_FACTOR_WEBAUTHN));
        }
    }

    @EventListener
    public void onLoginFailure(AbstractAuthenticationFailureEvent event) {
        Authentication authentication = event.getAuthentication();
        String cause = event.getException().getClass().getSimpleName();
        if (isFormLogin(authentication)) {
            rateLimiter.onLoginFailure(authentication.getName());
            auditPublisher.publish(AuditEvent.of(
                    AuditEventType.LOGIN_FAILED,
                    resolveUserId(authentication.getName()),
                    "user",
                    authentication.getName(),
                    "cause=" + cause));
            return;
        }
        if (isPasskeyLogin(authentication)) {
            JauthUser user = resolveByUserHandle(authentication);
            String username = user == null ? null : user.username();
            // 限流登记的条件语义：断言解析不出 jauth 用户（usernameless / 伪造句柄）则跳过，仅记审计
            if (username != null) {
                rateLimiter.onLoginFailure(username);
            }
            auditPublisher.publish(AuditEvent.of(
                    AuditEventType.LOGIN_FAILED,
                    user == null ? null : user.id(),
                    "user",
                    username,
                    DETAIL_FACTOR_WEBAUTHN + "; cause=" + cause));
        }
    }

    private static boolean isFormLogin(Authentication authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication.getClass());
    }

    private static boolean isPasskeyLogin(Authentication authentication) {
        return WebAuthnAuthentication.class.isAssignableFrom(authentication.getClass())
                || WebAuthnAuthenticationRequestToken.class.isAssignableFrom(authentication.getClass());
    }

    /** passkey 事件的用户反查：断言携带的 userHandle（= jauth_user.id 的 UTF-8 Bytes）→ 用户池。 */
    private @Nullable JauthUser resolveByUserHandle(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof Bytes userHandle)) {
            return null;
        }
        return this.userRepository.findById(JauthUserEntityRepository.userHandle(userHandle));
    }

    private @Nullable String resolveUserId(String username) {
        JauthUser user = userRepository.findByUsername(username);
        return user != null ? user.id() : null;
    }
}
