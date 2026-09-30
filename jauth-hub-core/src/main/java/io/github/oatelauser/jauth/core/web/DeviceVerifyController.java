package io.github.oatelauser.jauth.core.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 设备验证页：device flow 的用户确认端（05 票教学一句话）。
 *
 * <p>框架 device flow 的 verification_uri 落点即本页（AuthorizationServerSettings.deviceVerificationEndpoint
 * = /device/verify，B7 与表单提交路径对齐）；表单提交 user_code 给同路径 POST 由框架消费，本控制器只渲染视图。
 *
 * @author oatelauser
 */
@Controller
public class DeviceVerifyController {

    /** 视图名：模板位于 core 命名空间 templates 目录，解析前缀由装配方（B4）配置。 */
    static final String VIEW_DEVICE_VERIFY = "device-verify";

    private final EducationalFlag educational;

    public DeviceVerifyController(EducationalFlag educational) {
        this.educational = educational;
    }

    /**
     * 渲染设备验证页。
     *
     * @param model 视图模型
     * @return 视图名
     */
    @GetMapping("/device/verify")
    public String verify(Model model) {
        model.addAttribute("educational", educational.enabled());
        return VIEW_DEVICE_VERIFY;
    }
}
