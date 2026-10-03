package io.github.oatelauser.jauth.app.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 根路径落点（v1.2.1 修复 + 属性化）：表单登录成功默认目标与手敲域名都落 {@code /}，而默认链
 * anyRequest denyAll（spring-plus 红线）且此前无控制器认领——登录成功即 Chrome 403 页。
 *
 * <p><b>落点单一真源（用户红线：两种登录方式的落点必须恒等）</b>：表单与 passkey 的成功处理器
 * 默认目标都是 {@code /}（框架写死；webauthn DSL 无覆盖口），因此两方式的登录后去向在本类
 * **结构性收敛为一处**——本类是唯一的落点路由，去向可经 {@code jauth-hub.app.home-path} 配置。
 * 禁止绕过本类给 formLogin 单边设 defaultSuccessUrl：表单有口子而 passkey 没有，单边配置即两
 * 方式落点分叉。协议链装配处（starter 的 formLogin 段）留有同义守卫注释。
 *
 * <p>v1.5 B5b：默认落点随 SSR 退场改指 SPA 看板（/front/selfservice/apps）；本类同时是 app 包的
 * 302 目标构建共径（{@link #frontTarget}，v1.4 B4 皮肤旗标助手的同款语义迁入）。
 *
 * @author oatelauser
 */
@Controller
public class RootController {

    /** 登录后默认落点：SPA 看板页（自助门户的自然首页）。 */
    static final String DEFAULT_HOME_PATH = "/front/selfservice/apps";

    private final String homePath;

    public RootController(@Value("${jauth-hub.app.home-path:" + DEFAULT_HOME_PATH + "}") String homePath) {
        this.homePath = homePath;
    }

    @GetMapping("/")
    public String home() {
        return "redirect:" + this.homePath;
    }

    /**
     * 302 目标构建（app 包内共径）：必须走 {@link HttpServletRequest#getQueryString()} 而非重组参数——
     * 查询串的拼接顺序与编码（空格、多值）逐字透传给 SPA，空查询串不加尾缀 {@code ?}。
     */
    static String frontTarget(String frontPath, HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null || query.isEmpty() ? frontPath : frontPath + "?" + query;
    }
}
