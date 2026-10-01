package io.github.oatelauser.jauth.core.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * scope 声明式校验门（v1.2 C4）：{@link RequiresScope} 方法在当前主体 authorities 无对应 scope 时拒。
 *
 * <p><b>双形态认键</b>：scope 原串（冒号格式 = spring-plus 权限键形态，spring-plus 声明式鉴权语义直接
 * 可判）与 {@code SCOPE_} 前缀（rs-starter 内省与 Spring Security scope→authority 映射的默认形态）都放行；
 * rs-starter 自定义 authority-prefix 的宿主走手写规则（边界在 examples README 文档化）。
 *
 * <p><b>抛 {@link AccessDeniedException}（生态标准异常）而非 jauth 自有错误码</b>：从 MVC 层抛出会
 * 穿透到宿主过滤链的 ExceptionTranslationFilter 渲染 403（spring-plus SecurityExceptionAdvice 与
 * Boot 默认 AccessDeniedHandler 都正确处理；jauth 自有 JauthResponseAdvice 不拦它）。宿主若有
 * catch-all advice（如 {@code @ExceptionHandler(Throwable.class)}）需自行 rethrow 本异常，否则 403
 * 语义被吞成 500。
 *
 * <p><b>非注解方法零开销直通</b>：先验 HandlerMethod 与注解再触达 SecurityContext（照 SudoInterceptor
 * 形态）。无开关——注解不挂即零行为（与 passkey/sudo 的条件装配不同：声明式校验不改变任何既有请求路径）。
 *
 * @author oatelauser
 */
public class RequiresScopeInterceptor implements HandlerInterceptor {

    /** scope → authority 默认前缀（rs-starter 与 Spring Security 的 JwtGrantedAuthoritiesConverter 同值）。 */
    private static final String SCOPE_AUTHORITY_PREFIX = "SCOPE_";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequiresScope requiresScope = handlerMethod.getMethodAnnotation(RequiresScope.class);
        if (requiresScope == null) {
            return true;
        }
        if (hasScope(SecurityContextHolder.getContext().getAuthentication(), requiresScope.value())) {
            return true;
        }
        throw new AccessDeniedException("requires scope: " + requiresScope.value());
    }

    /** 双形态认键：authorities 含 scope 原串（spring-plus 权限键）或 SCOPE_ 前缀（Spring Security 默认映射）即放行。 */
    private static boolean hasScope(@Nullable Authentication authentication, String scope) {
        if (authentication == null) {
            return false;
        }
        String prefixed = SCOPE_AUTHORITY_PREFIX + scope;
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String granted = authority.getAuthority();
            if (scope.equals(granted) || prefixed.equals(granted)) {
                return true;
            }
        }
        return false;
    }
}
