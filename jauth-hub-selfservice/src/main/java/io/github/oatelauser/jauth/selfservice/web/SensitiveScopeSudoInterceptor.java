package io.github.oatelauser.jauth.selfservice.web;

import io.github.oatelauser.jauth.core.audit.AuditEvent;
import io.github.oatelauser.jauth.core.audit.AuditEventPublisher;
import io.github.oatelauser.jauth.core.audit.AuditEventType;
import io.github.oatelauser.jauth.core.response.JauthException;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.SudoGate;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.web.RequiresScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.util.Assert;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 敏感 scope 的 sudo 联动门（v1.3 D2，用户拍板新6：@RequiresScope 要求的 scope 标 sensitive 即过
 * SudoGate，fail-closed）：方法标 {@code @RequiresScope(sensitive = true)} 且本拦截器在场
 * （= sudo 开）时，scope 校验（starter 的 RequiresScopeInterceptor）通过后仍须强认证 TTL 内。
 *
 * <p><b>当下零生产消费端</b>：sensitive 位目前仅注解携带（C4 记档），首个敏感 API 面挂上注解即被本门
 * 覆盖——机制先行，行为由单测钉死。阻断形态与 {@link SudoInterceptor} 全同（A0515 JSON + SUDO_REQUIRED
 * 审计），页面 JS 无需区分两门。
 *
 * <p>两拦截器无顺序契约：scope 缺失（403 AccessDeniedException）与 sudo 过期（A0515）任一先拒皆可。
 *
 * @author oatelauser
 */
public class SensitiveScopeSudoInterceptor implements HandlerInterceptor {

    private final SudoGate sudoGate;

    private final AuditEventPublisher auditPublisher;

    private final UserRepository userRepository;

    public SensitiveScopeSudoInterceptor(
            SudoGate sudoGate, AuditEventPublisher auditPublisher, UserRepository userRepository) {
        Assert.notNull(sudoGate, "sudoGate cannot be null");
        Assert.notNull(auditPublisher, "auditPublisher cannot be null");
        Assert.notNull(userRepository, "userRepository cannot be null");
        this.sudoGate = sudoGate;
        this.auditPublisher = auditPublisher;
        this.userRepository = userRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequiresScope requiresScope = handlerMethod.getMethodAnnotation(RequiresScope.class);
        if (requiresScope == null || !requiresScope.sensitive()) {
            return true;
        }
        String username = request.getRemoteUser();
        if (username != null && this.sudoGate.isFresh(username)) {
            return true;
        }
        this.auditPublisher.publish(AuditEvent.of(
                AuditEventType.SUDO_REQUIRED,
                resolveUserId(username),
                "user",
                username,
                "path=" + request.getRequestURI() + " trigger=sensitive-scope " + requiresScope.value()));
        throw new JauthException(SelfServiceErrorCode.A0515);
    }

    private @Nullable String resolveUserId(@Nullable String username) {
        if (username == null) {
            return null;
        }
        JauthUser user = this.userRepository.findByUsername(username);
        return user == null ? null : user.id();
    }
}
