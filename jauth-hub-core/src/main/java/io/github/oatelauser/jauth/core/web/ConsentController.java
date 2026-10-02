package io.github.oatelauser.jauth.core.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Consent 页：授权码流程第二步，scope 交集在此成形（05 票教学一句话）。
 *
 * <p>照 SAS 官方 consent 页示例模式：框架在需要授权时重定向到本页并携带 client_id/state/scope 请求参数；表单回传目标是<b>授权端点
 * /oauth2/authorize</b>（框架在该 POST 上收 client_id/state/scope 后下发授权码），本页只有 GET——POST 打到 /oauth2/consent 即 405；
 * 勾选 scope 与发行令牌取交集的语义由授权服务链落地，本控制器只把视图与表单模型组装正确。
 *
 * <p>装配逻辑（org 三态、已授权徽标、clientName 解析、org 会话暂存副作用）统一在
 * {@link ConsentPageAssembler}——v1.4 B1 起 JSON 状态面（{@link ConsentStateController}）与 SSR 皮同源装配，
 * 行为逐字段一致。
 *
 * <p>皮肤守卫（v1.4 B4）：{@link TrustSkinFlag} 开启时 GET 302 到 /front/consent 的 SPA 皮
 * （client_id/state/scope/org 查询串原样转发，SPA 再经 /api/consent 取装配状态），默认 ssr 零行为变化。
 *
 * @author oatelauser
 */
@Controller
public class ConsentController {

    /** 视图名：模板位于 core 命名空间 templates 目录，解析前缀由装配方配置。 */
    static final String VIEW_CONSENT = "consent";

    /** front 皮肤路由（v1.4 B4，与 jauth-hub-front 的路由 base /front/ 对齐）。 */
    static final String FRONT_CONSENT_PATH = "/front/consent";

    private final ConsentPageAssembler assembler;

    private final TrustSkinFlag trustSkin;

    public ConsentController(ConsentPageAssembler assembler, TrustSkinFlag trustSkin) {
        this.assembler = assembler;
        this.trustSkin = trustSkin;
    }

    /**
     * 渲染授权确认页（trust-skin=front 时 302 到 SPA 皮）。
     *
     * @param clientId 框架重定向携带的 client_id 参数
     * @param state 框架重定向携带的 state 参数（防 CSRF，表单回传）
     * @param scopeParams 框架重定向携带的 scope 参数（空格拼接单值或多值）
     * @param orgParam org 选择器的重入参数（多候选时指定所选 org，须在候选集内）
     * @param principal 当前登录主体（consent 页必在认证后到达；缺席按无 org 上下文渲染）
     * @param session 会话（org 选择的暂存载体）
     * @param model 视图模型
     * @param request 当前请求（查询串原样转发给 SPA）
     * @return 视图名或重定向指令
     */
    @GetMapping("/oauth2/consent")
    public String consent(
            @RequestParam("client_id") String clientId,
            @RequestParam("state") String state,
            @RequestParam("scope") List<String> scopeParams,
            @RequestParam(value = "org", required = false) @Nullable String orgParam,
            @Nullable Authentication principal,
            HttpSession session,
            Model model,
            HttpServletRequest request) {
        String frontView = frontSkinEntry(request);
        if (frontView != null) {
            return frontView;
        }
        ConsentPageAssembler.ConsentPageModel page =
                this.assembler.assemble(clientId, state, scopeParams, orgParam, principal, session);
        model.addAttribute("clientId", page.clientId());
        model.addAttribute("state", page.state());
        model.addAttribute("clientName", page.clientName());
        model.addAttribute("educational", page.educational());
        model.addAttribute("orgGuide", page.orgGuide());
        model.addAttribute("orgChoices", page.orgChoices());
        model.addAttribute("orgBadge", page.orgBadge());
        model.addAttribute("scopes", page.scopes());
        return VIEW_CONSENT;
    }

    /**
     * 皮肤守卫（v1.4 B4，四页同款分支）：front 开 → 302 到 SPA 路由；默认 ssr 返回 null 走 SSR 视图渲染。
     */
    private @Nullable String frontSkinEntry(HttpServletRequest request) {
        if (!trustSkin.frontEnabled()) {
            return null;
        }
        return "redirect:" + TrustSkinFlag.frontTarget(FRONT_CONSENT_PATH, request);
    }
}
