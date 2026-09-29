package io.github.oatelauser.jauth.core.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 登录页：表单认证建立 session、auth_time 在此产生（05 票教学一句话）。
 *
 * <p>POST /login 的消费与 CSRF 校验由 Spring Security formLogin 过滤链承担（B4 装配），本控制器只渲染视图； 错误提示走 {@code
 * ?error} 参数（框架登录失败后重定向回本页携带）。
 *
 * @author oatelauser
 */
@Controller
public class LoginController {

    /** 视图名：模板位于 core 命名空间 templates 目录，解析前缀由装配方（B4）配置。 */
    static final String VIEW_LOGIN = "login";

    private final EducationalFlag educational;

    public LoginController(EducationalFlag educational) {
        this.educational = educational;
    }

    /**
     * 渲染登录页。
     *
     * @param model 视图模型
     * @return 视图名
     */
    @GetMapping("/login")
    public String login(Model model) {
        model.addAttribute("educational", educational.enabled());
        return VIEW_LOGIN;
    }
}
