package io.github.oatelauser.jauth.app.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 根路径落点（v1.2.1 修复）：表单登录成功默认目标与手敲域名都落 {@code /}，而默认链 anyRequest
 * denyAll（spring-plus 红线）且此前无控制器认领——登录成功即 Chrome 403 页。落点重定向看板
 * （自助门户的自然首页）；未认证访问会先被看板的认证要求带回登录页，登录后经保存请求回到本跳转，
 * 语义闭环。v1.0 起存在，用户此前恒从 /demo 进入故未暴露。
 *
 * @author oatelauser
 */
@Controller
public class RootController {

    /** 登录后落点：看板页。 */
    static final String HOME_REDIRECT = "redirect:/selfservice/apps";

    @GetMapping("/")
    public String home() {
        return HOME_REDIRECT;
    }
}
