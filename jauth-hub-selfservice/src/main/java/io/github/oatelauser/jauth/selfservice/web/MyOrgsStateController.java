package io.github.oatelauser.jauth.selfservice.web;

import io.github.oatelauser.jauth.core.org.OrgMembership;
import io.github.oatelauser.jauth.core.org.OrgService;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 我的组织页 JSON 状态面（v1.5 B1a，路径族规约：页面路径加 {@code /api} 前缀）：{@code GET
 * /api/selfservice/my-orgs} 与 SSR 页（{@link MyOrgsController#page}）同口径——orgsSupported = 服务在场性、
 * 非池内主体与服务缺席同态为空列表，行 = {@link OrgMembership}（role 出枚举名如 OWNER，i18n 文案归前端
 * 字典）。安全边界同 SSR 页（部署方 default 链，starter 链不动）。
 *
 * <p>B3 前端消费契约：{@code data:{ educational, orgsSupported, orgs, csrfToken, csrfHeaderName }}。
 *
 * @author oatelauser
 */
@RestController
public class MyOrgsStateController {

    private final @Nullable OrgService orgService;

    private final UserRepository userRepository;

    private final EducationalFlag educational;

    private final ResponseRenderer responseRenderer;

    /**
     * EI_EXPOSE_REP2 定向豁免：OrgService 是容器单例门面（Spring 注入通行形态，构造后无可变面暴露——
     * MyOrgsController 同款豁免）。
     */
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(value = "EI_EXPOSE_REP2")
    public MyOrgsStateController(
            @Nullable OrgService orgService,
            UserRepository userRepository,
            EducationalFlag educational,
            ResponseRenderer responseRenderer) {
        this.orgService = orgService;
        this.userRepository = userRepository;
        this.educational = educational;
        this.responseRenderer = responseRenderer;
    }

    /**
     * 我的组织页状态。
     *
     * @param principal 当前登录主体（非池内主体即空列表态，同 SSR 页）
     * @param request 请求（取 CSRF 惰性请求属性）
     * @return 统一包装的页面状态
     */
    @GetMapping(value = "/api/selfservice/my-orgs", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object state(@Nullable Principal principal, HttpServletRequest request) {
        List<OrgMembership> orgs = List.of();
        JauthUser user = principal == null ? null : this.userRepository.findByUsername(principal.getName());
        if (this.orgService != null && user != null) {
            orgs = this.orgService.findByUser(user.id());
        }
        CsrfPayload csrf = CsrfPayload.from(request);
        return this.responseRenderer.renderSuccess(new MyOrgsState(
                this.educational.enabled(), this.orgService != null, orgs, csrf.csrfToken(), csrf.csrfHeaderName()));
    }

    /** 我的组织页状态载荷（字段名即 B3 前端契约；OrgMembership.role 序列化为枚举名）。 */
    public record MyOrgsState(
            boolean educational,
            boolean orgsSupported,
            List<OrgMembership> orgs,
            @Nullable String csrfToken,
            @Nullable String csrfHeaderName) {

        /** orgs 防御性拷贝（SpotBugs EI_EXPOSE_REP：出入均不可变）。 */
        public MyOrgsState {
            orgs = orgs == null ? List.of() : List.copyOf(orgs);
        }
    }
}
