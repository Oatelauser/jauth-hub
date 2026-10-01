package io.github.oatelauser.jauth.selfservice.web;

import io.github.oatelauser.jauth.core.audit.AuditEvent;
import io.github.oatelauser.jauth.core.audit.AuditEventPublisher;
import io.github.oatelauser.jauth.core.audit.AuditEventType;
import io.github.oatelauser.jauth.core.response.JauthException;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.SudoGate;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.web.RequiresSudo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.util.Assert;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * sudo 拦截门（v1.2 C3）：{@link RequiresSudo} 方法在强认证 TTL 外触达即拒（A0515 JSON）。
 *
 * <p><b>只抛不重定向</b>（决议）：敏感端点全是页面 fetch 调的 JSON API，fetch 会跟随 302 把登录/验证页
 * HTML 当 JSON 解析——分叉是错的；统一抛 {@link JauthException}（JauthResponseAdvice 渲染家族失败体），
 * 页面 JS 认 A0515 自行跳验证页（带 returnTo）。
 *
 * <p><b>非注解方法零开销直通</b>：先验 HandlerMethod 与注解再触达 SudoGate（每次判定一次用户查询，省在
 * 93% 以上的普通请求上）。拦截器仅 sudo 开时由本模块 autoconfig 注册（SudoGate bean 在场为条件）。
 *
 * <p>阻断即审计（{@code SUDO_REQUIRED}，detail 记目标路径）；通过不另记——passkey 打点已有
 * LOGIN_SUCCESS factor=webauthn 可见。actor 反查照桥形态：username 是唯一稳定键，查不到记 null。
 *
 * @author oatelauser
 */
public class SudoInterceptor implements HandlerInterceptor {

    private final SudoGate sudoGate;

    private final AuditEventPublisher auditPublisher;

    private final UserRepository userRepository;

    public SudoInterceptor(SudoGate sudoGate, AuditEventPublisher auditPublisher, UserRepository userRepository) {
        Assert.notNull(sudoGate, "sudoGate cannot be null");
        Assert.notNull(auditPublisher, "auditPublisher cannot be null");
        Assert.notNull(userRepository, "userRepository cannot be null");
        this.sudoGate = sudoGate;
        this.auditPublisher = auditPublisher;
        this.userRepository = userRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)
                || handlerMethod.getMethodAnnotation(RequiresSudo.class) == null) {
            return true;
        }
        // 主体名缺席（未认证边角）不区别对待：fail-closed，同样拦下
        String username = request.getRemoteUser();
        if (username != null && this.sudoGate.isFresh(username)) {
            return true;
        }
        this.auditPublisher.publish(AuditEvent.of(
                AuditEventType.SUDO_REQUIRED, resolveUserId(username), "user", username, detail(request)));
        throw new JauthException(SelfServiceErrorCode.A0515);
    }

    private static String detail(HttpServletRequest request) {
        return "path=" + request.getRequestURI();
    }

    private @Nullable String resolveUserId(@Nullable String username) {
        if (username == null) {
            return null;
        }
        JauthUser user = this.userRepository.findByUsername(username);
        return user == null ? null : user.id();
    }
}
