package io.github.oatelauser.jauth.selfservice.web;

import io.github.oatelauser.jauth.core.user.SudoGate;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import io.github.oatelauser.jauth.core.web.PasskeyFlag;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * sudo 验证页（v1.2 C3）：敏感操作被 A0515 拦下后的 passkey 就地升权页——已认证会话内走
 * {@code POST /login/webauthn} 完成断言（C1 已验：框架成功处理会 changeSessionId 但不换 session 对象，
 * CSRF token 存活），成功后 JS 跳回 returnTo 指向的原表单页，<b>用户重填重交</b>（POST 不重放，决议）。
 *
 * <p><b>开关门控</b>（照 {@link PasskeyController} 形态）：SudoGate bean（= sudo 开）与 passkey 开关
 * 任一缺席即渲染"未启用"提示（200，不 500）——只看 SudoGate 不够：宿主自备 gate 而 passkey 关时，
 * 验证按钮指向的 /login/webauthn 根本不在链上。
 *
 * <p><b>returnTo 只认本站路径</b>（以 {@code /} 开头且非 {@code //}——协议相对 URL 会跳出本站），其余
 * （外站 URL、空、诡形）一律回退看板。服务端渲染前校验：该值要进 JS 做跳转目标，页面侧不再二次判。
 *
 * <p><b>安全边界</b>：路径不在 jauth 协议链认领清单内，认证由部署方 default 链负责（同其余自助页）。
 *
 * @author oatelauser
 */
@Controller
public class SudoController {

    /** 视图名（selfservice 命名空间模板，本模块视图解析器白名单认领）。 */
    public static final String VIEW_SUDO = "sudo";

    /** 回退目标：看板页（returnTo 不可信时的安全落点）。 */
    static final String FALLBACK_RETURN_TO = "/selfservice/apps";

    private final ObjectProvider<SudoGate> sudoGate;

    private final PasskeyFlag passkey;

    private final EducationalFlag educational;

    public SudoController(ObjectProvider<SudoGate> sudoGate, PasskeyFlag passkey, EducationalFlag educational) {
        this.sudoGate = sudoGate;
        this.passkey = passkey;
        this.educational = educational;
    }

    /**
     * sudo 验证页。
     *
     * @param returnTo 验证成功后的回跳路径（服务端校验，非法回退看板）
     * @param model 视图模型
     * @return 视图名
     */
    @GetMapping("/selfservice/sudo")
    public String page(@RequestParam(value = "returnTo", required = false) @Nullable String returnTo, Model model) {
        model.addAttribute("educational", this.educational.enabled());
        model.addAttribute("sudoEnabled", this.sudoGate.getIfAvailable() != null && this.passkey.enabled());
        model.addAttribute("returnTo", safeReturnTo(returnTo));
        return VIEW_SUDO;
    }

    /** 本站路径判定："/" 开头排除 "//"（协议相对 = 外站）；其余一律看板。 */
    private static String safeReturnTo(@Nullable String returnTo) {
        if (returnTo == null || !returnTo.startsWith("/") || returnTo.startsWith("//")) {
            return FALLBACK_RETURN_TO;
        }
        return returnTo;
    }
}
