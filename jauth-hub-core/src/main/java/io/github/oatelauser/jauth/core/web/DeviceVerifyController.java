package io.github.oatelauser.jauth.core.web;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 设备验证页：device flow 的用户确认端（05 票教学一句话）。
 *
 * <p>框架 device flow 的 verification_uri 落点即本页（AuthorizationServerSettings.deviceVerificationEndpoint
 * = /device/verify，B7 与表单提交路径对齐）；表单提交 user_code 给同路径 POST 由框架消费，本控制器只渲染视图。
 *
 * <p>皮肤守卫（v1.4 B4）：{@link TrustSkinFlag} 开启时 GET 302 到 /front/device-verify 的 SPA 皮
 * （查询串原样转发，SPA 再经 /api/device/verify 取状态、POST /device/verify 提交 user_code），默认 ssr 零行为变化。
 *
 * @author oatelauser
 */
@Controller
public class DeviceVerifyController {

    /** 视图名：模板位于 core 命名空间 templates 目录，解析前缀由装配方（B4）配置。 */
    static final String VIEW_DEVICE_VERIFY = "device-verify";

    /** front 皮肤路由（v1.4 B4，与 jauth-hub-front 的路由 base /front/ 对齐）。 */
    static final String FRONT_DEVICE_VERIFY_PATH = "/front/device-verify";

    private final EducationalFlag educational;

    private final TrustSkinFlag trustSkin;

    public DeviceVerifyController(EducationalFlag educational, TrustSkinFlag trustSkin) {
        this.educational = educational;
        this.trustSkin = trustSkin;
    }

    /**
     * 渲染设备验证页（trust-skin=front 时 302 到 SPA 皮）。
     *
     * @param model 视图模型
     * @param request 当前请求（查询串原样转发给 SPA）
     * @return 视图名或重定向指令
     */
    @GetMapping("/device/verify")
    public String verify(Model model, HttpServletRequest request) {
        String frontView = frontSkinEntry(request);
        if (frontView != null) {
            return frontView;
        }
        model.addAttribute("educational", educational.enabled());
        return VIEW_DEVICE_VERIFY;
    }

    /**
     * 皮肤守卫（v1.4 B4，四页同款分支）：front 开 → 302 到 SPA 路由；默认 ssr 返回 null 走 SSR 视图渲染。
     */
    private @Nullable String frontSkinEntry(HttpServletRequest request) {
        if (!trustSkin.frontEnabled()) {
            return null;
        }
        return "redirect:" + TrustSkinFlag.frontTarget(FRONT_DEVICE_VERIFY_PATH, request);
    }
}
