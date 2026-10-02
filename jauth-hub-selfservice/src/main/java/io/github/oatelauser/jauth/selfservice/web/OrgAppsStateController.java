package io.github.oatelauser.jauth.selfservice.web;

import io.github.oatelauser.jauth.core.org.Org;
import io.github.oatelauser.jauth.core.org.OrgRepository;
import io.github.oatelauser.jauth.core.org.OrgService;
import io.github.oatelauser.jauth.core.response.JauthErrorCode;
import io.github.oatelauser.jauth.core.response.JauthException;
import io.github.oatelauser.jauth.core.response.ResponseRenderer;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.web.CsrfPayload;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * org 应用页 JSON 状态面（v1.5 B1b，路径族规约：页面路径加 {@code /api} 前缀）：{@code GET
 * /api/selfservice/orgs/{orgId}/apps} 与 SSR 页（{@link OrgAppsController#page}）同口径——orgAppsSupported =
 * 三域 bean 在场性、OWNER 门在控制器入口（A0508，先于 org 存在性不泄露，与 {@link OrgAppsController} 既有
 * JSON 操作端点同形态）、apps 行 = {@link OwnedAppService#listOrg} 直出（SSR 页模型同源，无二次装配）。
 * 安全边界同 SSR 页（部署方 default 链，starter 链不动）。
 *
 * <p>B3 前端消费契约：{@code data:{ educational, orgAppsSupported, org, apps, csrfToken, csrfHeaderName }}；
 * 依赖缺席（supported=false）时 org=null、apps 空列表（SSR 属性缺席语义在 JSON 面收敛为常驻字段/空集）。
 *
 * @author oatelauser
 */
@RestController
public class OrgAppsStateController {

    private final @Nullable OwnedAppService ownedAppService;

    private final @Nullable OrgService orgService;

    private final @Nullable OrgRepository orgRepository;

    private final UserRepository userRepository;

    private final EducationalFlag educational;

    private final ResponseRenderer responseRenderer;

    /**
     * EI_EXPOSE_REP2 定向豁免：服务/仓储是容器单例门面（Spring 注入通行形态，构造后无可变面暴露——
     * OrgAppsController 同款豁免）。
     */
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(value = "EI_EXPOSE_REP2")
    public OrgAppsStateController(
            @Nullable OwnedAppService ownedAppService,
            @Nullable OrgService orgService,
            @Nullable OrgRepository orgRepository,
            UserRepository userRepository,
            EducationalFlag educational,
            ResponseRenderer responseRenderer) {
        this.ownedAppService = ownedAppService;
        this.orgService = orgService;
        this.orgRepository = orgRepository;
        this.userRepository = userRepository;
        this.educational = educational;
        this.responseRenderer = responseRenderer;
    }

    /**
     * org 应用页状态。
     *
     * @param orgId 路径 org id
     * @param principal 当前登录主体（服务在场时须在池，同 SSR 页）
     * @param request 请求（取 CSRF 惰性请求属性）
     * @return 统一包装的页面状态
     */
    @GetMapping(value = "/api/selfservice/orgs/{orgId}/apps", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object state(
            @PathVariable("orgId") String orgId, @Nullable Principal principal, HttpServletRequest request) {
        boolean supported = this.ownedAppService != null && this.orgService != null && this.orgRepository != null;
        Org org = null;
        List<OwnedAppService.OwnedApp> apps = List.of();
        if (supported) {
            JauthUser user = requireUser(principal);
            requireOwner(orgId, user);
            org = requireOrg(orgId);
            apps = this.ownedAppService.listOrg(orgId);
        }
        CsrfPayload csrf = CsrfPayload.from(request);
        return this.responseRenderer.renderSuccess(new OrgAppsState(
                this.educational.enabled(),
                supported,
                org == null ? null : new OrgView(org.id(), org.name()),
                apps,
                csrf.csrfToken(),
                csrf.csrfHeaderName()));
    }

    private void requireOwner(String orgId, JauthUser user) {
        if (!this.orgService.isOwner(orgId, user.id())) {
            throw new JauthException(JauthErrorCode.A0508);
        }
    }

    private Org requireOrg(String orgId) {
        Org org = this.orgRepository.findById(orgId);
        if (org == null) {
            throw new JauthException(JauthErrorCode.B0502);
        }
        return org;
    }

    private JauthUser requireUser(@Nullable Principal principal) {
        if (principal == null) {
            throw new JauthException(SelfServiceErrorCode.A0503);
        }
        JauthUser user = this.userRepository.findByUsername(principal.getName());
        if (user == null) {
            throw new JauthException(SelfServiceErrorCode.A0503);
        }
        return user;
    }

    /** org 应用页状态载荷（字段名即 B3 前端契约；OwnedApp 行与 SSR 页模型同形）。 */
    public record OrgAppsState(
            boolean educational,
            boolean orgAppsSupported,
            @Nullable OrgView org,
            List<OwnedAppService.OwnedApp> apps,
            @Nullable String csrfToken,
            @Nullable String csrfHeaderName) {

        /** apps 防御性拷贝（SpotBugs EI_EXPOSE_REP：出入均不可变）。 */
        public OrgAppsState {
            apps = apps == null ? List.of() : List.copyOf(apps);
        }
    }
}
