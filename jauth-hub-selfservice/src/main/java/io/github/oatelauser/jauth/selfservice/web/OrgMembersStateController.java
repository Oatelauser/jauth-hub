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
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * org 成员管理页 JSON 状态面（v1.5 B1b，路径族规约：页面路径加 {@code /api} 前缀）：{@code GET
 * /api/selfservice/orgs/{orgId}/members} 与 SSR 页（{@link OrgMembersController#page}）同口径——OWNER 门在
 * {@link OrgService#listMembers} 服务层单点（A0508，v1.3 D3，org 存在性不泄露，与 {@link OrgMembersController}
 * 既有 JSON 操作端点同门径）、members 行复用其包内 statics。与 SSR 的差异：状态面另带 org 投影（orgId/orgName，
 * 供前端页头渲染——SSR 页只有路径 orgId），故 supported 需 OrgRepository 在场。安全边界同 SSR 页（部署方
 * default 链，starter 链不动）。
 *
 * <p>B3 前端消费契约：{@code data:{ educational, membersSupported, org, members, csrfToken, csrfHeaderName }}；
 * 依赖缺席（supported=false）时 org=null、members 空列表。
 *
 * @author oatelauser
 */
@RestController
public class OrgMembersStateController {

    private final @Nullable OrgService orgService;

    private final @Nullable OrgRepository orgRepository;

    private final UserRepository userRepository;

    private final EducationalFlag educational;

    private final ResponseRenderer responseRenderer;

    /**
     * EI_EXPOSE_REP2 定向豁免：服务/仓储是容器单例门面（Spring 注入通行形态，构造后无可变面暴露——
     * OrgMembersController 同款豁免）。
     */
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(value = "EI_EXPOSE_REP2")
    public OrgMembersStateController(
            @Nullable OrgService orgService,
            @Nullable OrgRepository orgRepository,
            UserRepository userRepository,
            EducationalFlag educational,
            ResponseRenderer responseRenderer) {
        this.orgService = orgService;
        this.orgRepository = orgRepository;
        this.userRepository = userRepository;
        this.educational = educational;
        this.responseRenderer = responseRenderer;
    }

    /**
     * 成员管理页状态。
     *
     * @param orgId 路径 org id
     * @param principal 当前登录主体（服务在场时须在池，同 SSR 页）
     * @param request 请求（取 CSRF 惰性请求属性）
     * @return 统一包装的页面状态
     */
    @GetMapping(value = "/api/selfservice/orgs/{orgId}/members", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object state(
            @PathVariable("orgId") String orgId, @Nullable Principal principal, HttpServletRequest request) {
        boolean supported = this.orgService != null && this.orgRepository != null;
        Org org = null;
        List<Map<String, Object>> members = List.of();
        if (supported) {
            JauthUser user = requireUser(principal);
            // 门径与 SSR 页同：memberRows 内 listMembers 先行（服务层 OWNER 门 A0508，org 存在性不泄露），
            // org 反查在后——OWNER 过门后 org 必在场，B0502 只是完整性兜底
            members = OrgMembersController.memberRows(orgId, user, this.orgService, this.userRepository);
            org = requireOrg(orgId);
        }
        CsrfPayload csrf = CsrfPayload.from(request);
        return this.responseRenderer.renderSuccess(new OrgMembersState(
                this.educational.enabled(),
                supported,
                org == null ? null : new OrgView(org.id(), org.name()),
                members,
                csrf.csrfToken(),
                csrf.csrfHeaderName()));
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

    /** 成员管理页状态载荷（字段名即 B3 前端契约；members 行与 SSR 页模型同形，role 出枚举名）。 */
    public record OrgMembersState(
            boolean educational,
            boolean membersSupported,
            @Nullable OrgView org,
            List<Map<String, Object>> members,
            @Nullable String csrfToken,
            @Nullable String csrfHeaderName) {

        /** members 防御性拷贝（SpotBugs EI_EXPOSE_REP：出入均不可变）。 */
        public OrgMembersState {
            members = members == null ? List.of() : List.copyOf(members);
        }
    }
}
