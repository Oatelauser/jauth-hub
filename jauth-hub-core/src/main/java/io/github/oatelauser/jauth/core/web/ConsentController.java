package io.github.oatelauser.jauth.core.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Consent 页路由（v1.5 B5b SSR 拆除）：GET 无条件 302 到 {@code /front/consent} 的 SPA 皮，
 * client_id/state/scope/org 查询串原样转发（SPA 再经 /api/consent 取装配状态，org 会话暂存副作用
 * 随之在状态面发生）。表单回传目标仍是授权端点 /oauth2/authorize（框架语义，与本路由无关）。
 *
 * @author oatelauser
 */
@Controller
public class ConsentController {

    /** front 皮肤路由（与 jauth-hub-front 的路由 base /front/ 对齐）。 */
    static final String FRONT_CONSENT_PATH = "/front/consent";

    /**
     * 授权确认页入口：302 到 SPA 皮。
     *
     * @param request 当前请求（查询串原样转发给 SPA）
     * @return 重定向指令
     */
    @GetMapping("/oauth2/consent")
    public String consent(HttpServletRequest request) {
        return "redirect:" + LoginController.frontTarget(FRONT_CONSENT_PATH, request);
    }
}
