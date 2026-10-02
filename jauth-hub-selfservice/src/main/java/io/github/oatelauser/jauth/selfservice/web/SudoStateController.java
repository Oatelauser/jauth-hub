package io.github.oatelauser.jauth.selfservice.web;

import io.github.oatelauser.jauth.core.response.ResponseRenderer;
import io.github.oatelauser.jauth.core.user.SudoGate;
import io.github.oatelauser.jauth.core.web.CsrfPayload;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import io.github.oatelauser.jauth.core.web.PasskeyFlag;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * sudo 验证页 JSON 状态面（v1.4 B1，SPEC §1 混合用法）：{@code GET /api/sudo?returnTo=} 与 SSR 页
 * （{@link SudoController}）同口径——returnTo 服务端消毒（复用 {@link SudoController#safeReturnTo}，
 * 非法值回退看板）、sudoEnabled = SudoGate bean 在场且 passkey 开。安全边界同 SSR 页：路径不在 jauth
 * 协议链认领清单内，认证由部署方 default 链负责（本 API 同理，starter 链不动）。
 *
 * <p>B3 前端消费契约：{@code data:{ educational, sudoEnabled, returnTo(已消毒), csrfToken, csrfHeaderName }}；
 * CSRF 字段可空语义见 {@link CsrfPayload}（宿主链未启用 Spring Security CSRF 时为 null）。
 *
 * @author oatelauser
 */
@RestController
public class SudoStateController {

    private final ObjectProvider<SudoGate> sudoGate;

    private final PasskeyFlag passkey;

    private final EducationalFlag educational;

    private final ResponseRenderer responseRenderer;

    public SudoStateController(
            ObjectProvider<SudoGate> sudoGate,
            PasskeyFlag passkey,
            EducationalFlag educational,
            ResponseRenderer responseRenderer) {
        this.sudoGate = sudoGate;
        this.passkey = passkey;
        this.educational = educational;
        this.responseRenderer = responseRenderer;
    }

    /**
     * sudo 验证页状态。
     *
     * @param returnTo 验证成功后的回跳路径（服务端消毒，非法回退看板）
     * @param request 请求（取 CSRF 惰性请求属性）
     * @return 统一包装的页面状态
     */
    @GetMapping(value = "/api/sudo", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object sudoState(
            @RequestParam(value = "returnTo", required = false) @Nullable String returnTo, HttpServletRequest request) {
        boolean sudoEnabled = this.sudoGate.getIfAvailable() != null && this.passkey.enabled();
        CsrfPayload csrf = CsrfPayload.from(request);
        return this.responseRenderer.renderSuccess(new SudoState(
                this.educational.enabled(),
                sudoEnabled,
                SudoController.safeReturnTo(returnTo),
                csrf.csrfToken(),
                csrf.csrfHeaderName()));
    }

    /** sudo 页状态载荷（字段名即 B3 前端契约）。 */
    public record SudoState(
            boolean educational,
            boolean sudoEnabled,
            String returnTo,
            @Nullable String csrfToken,
            @Nullable String csrfHeaderName) {}
}
