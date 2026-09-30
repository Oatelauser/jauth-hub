package io.github.oatelauser.jauth.core.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 请求上下文取 IP/UA 的共用小工具：发布实现补齐 {@link AuditEvent} 的 ip/userAgent 用。
 *
 * <p>无请求上下文（后台播种、测试直调、异步线程）返回 null——事件落库时两列为空，即票 07
 * "无请求上下文的事件留空并注明"的语义。
 *
 * @author oatelauser
 */
final class RequestAuditContext {

    private RequestAuditContext() {}

    /** 当前请求的远端地址；无请求上下文为 null。 */
    static @Nullable String currentIp() {
        HttpServletRequest request = currentRequest();
        return request != null ? request.getRemoteAddr() : null;
    }

    /** 当前请求的 User-Agent；无请求上下文为 null。 */
    static @Nullable String currentUserAgent() {
        HttpServletRequest request = currentRequest();
        return request != null ? request.getHeader("User-Agent") : null;
    }

    private static @Nullable HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }
}
