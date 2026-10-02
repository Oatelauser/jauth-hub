package io.github.oatelauser.jauth.core.web;

import io.github.oatelauser.jauth.core.response.ResponseRenderer;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备验证页 JSON 状态面（v1.4 B1，SPEC §1 混合用法）：{@code GET /api/device/verify} 与 SSR 页
 * （{@link DeviceVerifyController}）同口径——本页只有 educational 一个标志；认证归协议链 authenticated
 * （未认证 JSON 请求 401），由 starter 链认领。
 *
 * <p>B3 前端消费契约：{@code data:{ educational, csrfToken, csrfHeaderName }}。
 *
 * @author oatelauser
 */
@RestController
public class DeviceVerifyStateController {

    private final EducationalFlag educational;

    private final ResponseRenderer responseRenderer;

    public DeviceVerifyStateController(EducationalFlag educational, ResponseRenderer responseRenderer) {
        this.educational = educational;
        this.responseRenderer = responseRenderer;
    }

    /**
     * 设备验证页状态。
     *
     * @param request 请求（取 CSRF 惰性请求属性）
     * @return 统一包装的页面状态
     */
    @GetMapping(value = "/api/device/verify", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object verifyState(HttpServletRequest request) {
        CsrfPayload csrf = CsrfPayload.from(request);
        return this.responseRenderer.renderSuccess(
                new DeviceVerifyState(this.educational.enabled(), csrf.csrfToken(), csrf.csrfHeaderName()));
    }

    /** 设备验证页状态载荷（字段名即 B3 前端契约）。 */
    public record DeviceVerifyState(boolean educational, @Nullable String csrfToken, @Nullable String csrfHeaderName) {}
}
