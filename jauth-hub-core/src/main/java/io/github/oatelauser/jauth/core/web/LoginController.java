package io.github.oatelauser.jauth.core.web;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 登录页：表单认证建立 session、auth_time 在此产生（05 票教学一句话）。
 *
 * <p>POST /login 的消费与 CSRF 校验由 Spring Security formLogin 过滤链承担（B4 装配），本控制器只渲染视图； 错误提示走 {@code
 * ?error} 参数（框架登录失败后重定向回本页携带）。
 *
 * <p>passkey 开启时（v1.2 C2）额外渲染"使用通行密钥登录"按钮与内联脚本——可见性经 {@link PasskeyFlag} 注入，默认关。
 *
 * <p>皮肤守卫（v1.4 B4）：{@link TrustSkinFlag} 开启时 GET 302 到 /front/login 的 SPA 皮（?error 原样转发），
 * 默认 ssr 零行为变化。
 *
 * @author oatelauser
 */
@Controller
public class LoginController {

    /** 视图名：模板位于 core 命名空间 templates 目录，解析前缀由装配方（B4）配置。 */
    static final String VIEW_LOGIN = "login";

    /** front 皮肤路由（v1.4 B4，与 jauth-hub-front 的路由 base /front/ 对齐）。 */
    static final String FRONT_LOGIN_PATH = "/front/login";

    private final EducationalFlag educational;

    private final PasskeyFlag passkey;

    private final TrustSkinFlag trustSkin;

    public LoginController(EducationalFlag educational, PasskeyFlag passkey, TrustSkinFlag trustSkin) {
        this.educational = educational;
        this.passkey = passkey;
        this.trustSkin = trustSkin;
    }

    /**
     * 渲染登录页（trust-skin=front 时 302 到 SPA 皮）。
     *
     * @param model 视图模型
     * @param request 当前请求（查询串原样转发给 SPA）
     * @return 视图名或重定向指令
     */
    @GetMapping("/login")
    public String login(Model model, HttpServletRequest request) {
        String frontView = frontSkinEntry(request);
        if (frontView != null) {
            return frontView;
        }
        model.addAttribute("educational", educational.enabled());
        model.addAttribute("passkeyEnabled", passkey.enabled());
        return VIEW_LOGIN;
    }

    /**
     * 皮肤守卫（v1.4 B4，四页同款分支）：front 开 → 302 到 SPA 路由；默认 ssr 返回 null 走 SSR 视图渲染。
     */
    private @Nullable String frontSkinEntry(HttpServletRequest request) {
        if (!trustSkin.frontEnabled()) {
            return null;
        }
        return "redirect:" + TrustSkinFlag.frontTarget(FRONT_LOGIN_PATH, request);
    }
}
