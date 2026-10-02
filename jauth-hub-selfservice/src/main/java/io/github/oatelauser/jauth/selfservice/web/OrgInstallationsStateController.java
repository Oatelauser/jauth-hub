package io.github.oatelauser.jauth.selfservice.web;

import io.github.oatelauser.jauth.core.org.InstallationRepository;
import io.github.oatelauser.jauth.core.org.InstallationService;
import io.github.oatelauser.jauth.core.org.InstallationStatus;
import io.github.oatelauser.jauth.core.org.Org;
import io.github.oatelauser.jauth.core.org.OrgRepository;
import io.github.oatelauser.jauth.core.org.OrgService;
import io.github.oatelauser.jauth.core.response.JauthErrorCode;
import io.github.oatelauser.jauth.core.response.JauthException;
import io.github.oatelauser.jauth.core.response.ResponseRenderer;
import io.github.oatelauser.jauth.core.scope.ScopeCatalog;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.web.CsrfPayload;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import io.github.oatelauser.jauth.selfservice.web.OrgInstallationsController.InstallationRow;
import io.github.oatelauser.jauth.selfservice.web.OrgInstallationsController.ScopeItem;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 安装审批页 JSON 状态面（v1.5 B1b，路径族规约：页面路径加 {@code /api} 前缀）：{@code GET
 * /api/selfservice/orgs/{orgId}/installations} 与 SSR 页（{@link OrgInstallationsController#page}）同口径——
 * installationsSupported = 四域 bean 在场性（与 SSR 页同式，含 InstallationService——supported 是页级陈述，
 * 两张脸口径必须一致）、OWNER 门在控制器入口（A0508，先于 org 存在性不泄露）、行装配/scope 目录复用
 * {@link OrgInstallationsController} 的包内 statics（不复制）。与 SSR 的<b>有意差异</b>：status 出枚举名
 * （PENDING/REJECTED/APPROVED/REVOKED），不携带服务端拼好的 statusKey——i18n key 归前端字典（survey §4.3）。
 * scope 描述保持服务端 i18n 解析（B3 决议唯一例外）。安全边界同 SSR 页（部署方 default 链，starter 链不动）。
 *
 * <p>B3 前端消费契约：{@code data:{ educational, installationsSupported, org, pending, installations, scopes,
 * csrfToken, csrfHeaderName }}；依赖缺席（supported=false）时 org=null、两分区与 scope 目录空列表。
 *
 * @author oatelauser
 */
@RestController
public class OrgInstallationsStateController {

    private final @Nullable OrgService orgService;

    /** 仅参与 supported 判定（与 SSR 页同式）；行装配只读仓储，不走服务动作面。 */
    private final @Nullable InstallationService installationService;

    private final @Nullable InstallationRepository installationRepository;

    private final @Nullable OrgRepository orgRepository;

    private final UserRepository userRepository;

    private final RegisteredClientRepository clientRepository;

    private final ScopeCatalog scopeCatalog;

    private final MessageSource messageSource;

    private final EducationalFlag educational;

    private final ResponseRenderer responseRenderer;

    /**
     * EI_EXPOSE_REP2 定向豁免：服务/仓储是容器单例门面（Spring 注入通行形态，构造后无可变面暴露）。
     */
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(value = "EI_EXPOSE_REP2")
    public OrgInstallationsStateController(
            @Nullable OrgService orgService,
            @Nullable InstallationService installationService,
            @Nullable InstallationRepository installationRepository,
            @Nullable OrgRepository orgRepository,
            UserRepository userRepository,
            RegisteredClientRepository clientRepository,
            ScopeCatalog scopeCatalog,
            MessageSource messageSource,
            EducationalFlag educational,
            ResponseRenderer responseRenderer) {
        this.orgService = orgService;
        this.installationService = installationService;
        this.installationRepository = installationRepository;
        this.orgRepository = orgRepository;
        this.userRepository = userRepository;
        this.clientRepository = clientRepository;
        this.scopeCatalog = scopeCatalog;
        this.messageSource = messageSource;
        this.educational = educational;
        this.responseRenderer = responseRenderer;
    }

    /**
     * 安装审批页状态（PENDING 待批与全量两分区，同 SSR 页模型）。
     *
     * @param orgId 路径 org id
     * @param principal 当前登录主体（服务在场时须在池，同 SSR 页）
     * @param request 请求（取 CSRF 惰性请求属性）
     * @return 统一包装的页面状态
     */
    @GetMapping(value = "/api/selfservice/orgs/{orgId}/installations", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object state(
            @PathVariable("orgId") String orgId, @Nullable Principal principal, HttpServletRequest request) {
        boolean supported = domainBeansPresent();
        Org org = null;
        List<InstallationRow> installations = List.of();
        List<ScopeItem> scopes = List.of();
        if (supported) {
            JauthUser user = requireUser(principal);
            requireOwner(orgId, user);
            org = requireOrg(orgId);
            installations = OrgInstallationsController.installationRows(
                    orgId, this.installationRepository, this.clientRepository, this.userRepository);
            scopes = OrgInstallationsController.scopeItems(
                    LocaleContextHolder.getLocale(), this.scopeCatalog, this.messageSource);
        }
        // PENDING 分区 = 全量行按状态过滤（与 SSR 页同一表达式，两分区共用一形状）
        List<InstallationRow> pending = installations.stream()
                .filter(row -> row.status() == InstallationStatus.PENDING)
                .toList();
        CsrfPayload csrf = CsrfPayload.from(request);
        return this.responseRenderer.renderSuccess(new OrgInstallationsState(
                this.educational.enabled(),
                supported,
                org == null ? null : new OrgView(org.id(), org.name()),
                pending,
                installations,
                scopes,
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

    private boolean domainBeansPresent() {
        return this.orgService != null
                && this.installationService != null
                && this.installationRepository != null
                && this.orgRepository != null;
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

    /** 安装审批页状态载荷（字段名即 B3 前端契约；行与 SSR 页模型同形，status 出枚举名、无 statusKey）。 */
    public record OrgInstallationsState(
            boolean educational,
            boolean installationsSupported,
            @Nullable OrgView org,
            List<InstallationRow> pending,
            List<InstallationRow> installations,
            List<ScopeItem> scopes,
            @Nullable String csrfToken,
            @Nullable String csrfHeaderName) {

        /** 集合防御性拷贝（SpotBugs EI_EXPOSE_REP：出入均不可变）。 */
        public OrgInstallationsState {
            pending = pending == null ? List.of() : List.copyOf(pending);
            installations = installations == null ? List.of() : List.copyOf(installations);
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }
    }
}
