package io.github.oatelauser.jauth.core.web;

import io.github.oatelauser.jauth.core.response.ResponseRenderer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * consent 页 JSON 状态面（v1.4 B1，SPEC §1 混合用法）：{@code GET /api/consent} 参数与 SSR 页逐一同名，
 * 委托 {@link ConsentPageAssembler} 装配——org 三态、已授权徽标、<b>org 会话暂存副作用与 SSR 完全同路径发生</b>；
 * ResponseRenderer 统一包装（00000，SPEC §4 端点三分的自助类面）。认证与 SSR 同口径（协议链 authenticated，
 * 未认证 JSON 请求 401），由 starter 链认领。
 *
 * <p>B3 前端消费契约：{@code data:{ clientId, state, clientName, educational, orgGuide,
 * orgChoices:[{orgId,orgName,href}], orgBadge(可 null), scopes:[{name,description,checked,grantable,
 * alreadyGranted}], csrfToken, csrfHeaderName }}；orgChoices.href 为 SSR 页重入链接照装配器现值（前端可忽略）。
 *
 * @author oatelauser
 */
@RestController
public class ConsentStateController {

    private final ConsentPageAssembler assembler;

    private final ResponseRenderer responseRenderer;

    public ConsentStateController(ConsentPageAssembler assembler, ResponseRenderer responseRenderer) {
        this.assembler = assembler;
        this.responseRenderer = responseRenderer;
    }

    /**
     * consent 页状态。
     *
     * @param clientId 框架重定向携带的 client_id 参数
     * @param state 框架重定向携带的 state 参数（防 CSRF，表单回传）
     * @param scopeParams 框架重定向携带的 scope 参数（空格拼接单值或多值）
     * @param orgParam org 选择器的重入参数（多候选时指定所选 org，须在候选集内）
     * @param principal 当前登录主体（缺席按无 org 上下文装配）
     * @param session 会话（org 选择的暂存载体）
     * @param request 请求（取 CSRF 惰性请求属性）
     * @return 统一包装的页面状态
     */
    @GetMapping(value = "/api/consent", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object consentState(
            @RequestParam("client_id") String clientId,
            @RequestParam("state") String state,
            @RequestParam("scope") List<String> scopeParams,
            @RequestParam(value = "org", required = false) @Nullable String orgParam,
            @Nullable Authentication principal,
            HttpSession session,
            HttpServletRequest request) {
        ConsentPageAssembler.ConsentPageModel page =
                this.assembler.assemble(clientId, state, scopeParams, orgParam, principal, session);
        CsrfPayload csrf = CsrfPayload.from(request);
        return this.responseRenderer.renderSuccess(new ConsentState(
                page.clientId(),
                page.state(),
                page.clientName(),
                page.educational(),
                page.orgGuide(),
                page.orgChoices(),
                page.orgBadge(),
                page.scopes(),
                csrf.csrfToken(),
                csrf.csrfHeaderName()));
    }

    /** consent 页状态载荷（字段名即 B3 前端契约）。 */
    public record ConsentState(
            String clientId,
            String state,
            String clientName,
            boolean educational,
            boolean orgGuide,
            List<ConsentPageAssembler.OrgChoice> orgChoices,
            @Nullable String orgBadge,
            List<ConsentPageAssembler.ScopeItem> scopes,
            @Nullable String csrfToken,
            @Nullable String csrfHeaderName) {

        /** 集合防御性拷贝为不可变表（SpotBugs EI_EXPOSE_REP：PatRecord 同款取舍）。 */
        public ConsentState {
            orgChoices = List.copyOf(orgChoices);
            scopes = List.copyOf(scopes);
        }
    }
}
