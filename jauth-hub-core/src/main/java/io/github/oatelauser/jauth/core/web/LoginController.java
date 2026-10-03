package io.github.oatelauser.jauth.core.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 登录页路由（v1.5 B5b SSR 拆除）：GET 无条件 302 到 {@code /front/login} 的 SPA 皮，查询串
 * （{@code ?error} 等）原样转发。POST /login 的消费与 CSRF 校验仍由 Spring Security formLogin
 * 过滤链承担，本控制器不再持有任何渲染依赖。
 *
 * @author oatelauser
 */
@Controller
public class LoginController {

    /** front 皮肤路由（与 jauth-hub-front 的路由 base /front/ 对齐）。 */
    static final String FRONT_LOGIN_PATH = "/front/login";

    /**
     * 登录页入口：302 到 SPA 皮。
     *
     * @param request 当前请求（查询串原样转发给 SPA）
     * @return 重定向指令
     */
    @GetMapping("/login")
    public String login(HttpServletRequest request) {
        return "redirect:" + frontTarget(FRONT_LOGIN_PATH, request);
    }

    /**
     * 302 目标构建（v1.4 B4 皮肤旗标助手迁入）：必须走 {@link HttpServletRequest#getQueryString()}
     * 而非重组参数——框架重定向参数的拼接顺序与编码（空格、多值）逐字透传给 SPA，空查询串不加尾缀 {@code ?}。
     */
    static String frontTarget(String frontPath, HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null || query.isEmpty() ? frontPath : frontPath + "?" + query;
    }
}
