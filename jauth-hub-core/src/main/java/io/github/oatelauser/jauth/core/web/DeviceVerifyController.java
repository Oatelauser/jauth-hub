package io.github.oatelauser.jauth.core.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 设备验证页路由（v1.5 B5b SSR 拆除）：GET 无条件 302 到 {@code /front/device-verify} 的 SPA 皮，
 * 查询串原样转发（SPA 再经 /api/device/verify 取状态、POST /device/verify 提交 user_code——框架
 * device flow 的 verification_uri 落点语义不变）。
 *
 * @author oatelauser
 */
@Controller
public class DeviceVerifyController {

    /** front 皮肤路由（与 jauth-hub-front 的路由 base /front/ 对齐）。 */
    static final String FRONT_DEVICE_VERIFY_PATH = "/front/device-verify";

    /**
     * 设备验证页入口：302 到 SPA 皮。
     *
     * @param request 当前请求（查询串原样转发给 SPA）
     * @return 重定向指令
     */
    @GetMapping("/device/verify")
    public String verify(HttpServletRequest request) {
        return "redirect:" + LoginController.frontTarget(FRONT_DEVICE_VERIFY_PATH, request);
    }
}
