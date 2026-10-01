package io.github.oatelauser.jauth.app.web;

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
 * @author oatelauser
 */
@Controller
public class RootController {

    /** 登录后默认落点：看板页（自助门户的自然首页）。 */
    static final String DEFAULT_HOME_PATH = "/selfservice/apps";

    private final String homePath;

    public RootController(@Value("${jauth-hub.app.home-path:" + DEFAULT_HOME_PATH + "}") String homePath) {
        this.homePath = homePath;
    }

    @GetMapping("/")
    public String home() {
        return "redirect:" + this.homePath;
    }
}
