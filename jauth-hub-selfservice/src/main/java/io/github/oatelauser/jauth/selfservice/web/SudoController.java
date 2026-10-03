package io.github.oatelauser.jauth.selfservice.web;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * sudo 验证页路由（v1.2 C3；v1.5 B5b SSR 拆除）：GET 无条件 302 到 {@code /front/sudo} 的 SPA 皮，
 * returnTo 查询串原样转发（SPA 再经 /api/sudo 取状态，passkey 断言走框架端点 /login/webauthn——
 * 成功后 SPA 跳回 returnTo 指向的原表单页，用户重填重交的 v1.2 决议不变）。
 *
 * <p><b>returnTo 只认本站路径</b>（以 {@code /} 开头且非 {@code //}——协议相对 URL 会跳出本站），其余
 * （外站 URL、空、诡形）一律回退看板。消毒单点 {@link #safeReturnTo} 由 JSON 状态面
 * （{@link SudoStateController}）消费——SSR 皮退场后这里是纯路由，语义零变化。
 *
 * <p><b>安全边界</b>：路径不在 jauth 协议链认领清单内，认证由部署方 default 链负责（同其余自助页）。
 *
 * @author oatelauser
 */
@Controller
public class SudoController {

    /** 回退目标：看板页（returnTo 不可信时的安全落点）。 */
    static final String FALLBACK_RETURN_TO = "/selfservice/apps";

    /** front 皮肤路由（与 jauth-hub-front 的路由 base /front/ 对齐）。 */
    static final String FRONT_SUDO_PATH = "/front/sudo";

    /**
     * sudo 验证页入口：302 到 SPA 皮。
     *
     * @param request 当前请求（查询串原样转发给 SPA）
     * @return 重定向指令
     */
    @GetMapping("/selfservice/sudo")
    public String page(HttpServletRequest request) {
        return "redirect:" + frontTarget(FRONT_SUDO_PATH, request);
    }

    /**
     * 本站路径判定："/" 开头排除 "//"（协议相对 = 外站）；其余一律看板。
     * package-private 供同包 JSON 状态面（{@link SudoStateController}）复用——消毒口径单点。
     */
    static String safeReturnTo(@Nullable String returnTo) {
        if (returnTo == null || !returnTo.startsWith("/") || returnTo.startsWith("//")) {
            return FALLBACK_RETURN_TO;
        }
        return returnTo;
    }

    /**
     * 302 目标构建（selfservice 包内共径，v1.4 B4 皮肤旗标助手的同款语义迁入）：必须走
     * {@link HttpServletRequest#getQueryString()} 而非重组参数——查询串的拼接顺序与编码（空格、多值）
     * 逐字透传给 SPA，空查询串不加尾缀 {@code ?}。
     */
    static String frontTarget(String frontPath, HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null || query.isEmpty() ? frontPath : frontPath + "?" + query;
    }
}
