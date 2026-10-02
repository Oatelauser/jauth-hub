package io.github.oatelauser.jauth.selfservice.web;

import io.github.oatelauser.jauth.core.org.Installation;
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
import io.github.oatelauser.jauth.core.scope.ScopeDefinition;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 安装审批页（B11：流向 A 两步制的 OWNER 面，2026-09-30 拍板）。
 *
 * <p><b>页面</b>（GET /selfservice/orgs/{orgId}/installations，仅该 org OWNER）：PENDING 待批区（发起人名、
 * requested_scopes 勾选框默认全选——OWNER 可收窄勾选为 ceiling）+ 本 org 全量安装行（状态徽标、ceiling、
 * 审批人/时间）+ 面向 org 的安装发起表单（手填 client_id + 勾选 scope 目录全集；最小面不做浏览目录）。
 *
 * <p><b>动作面</b>（页内原生 JS 调 JSON 端点，照 my-apps 惯例）：request（任何登录用户可发起，发起人不要求
 * org 成员——控制点在 OWNER 审批不在发起，拍板原文）/approve（勾选集即 ceilingScopes，空勾选拒 A0511；⊆
 * requested、OWNER 门、状态门全由 InstallationService 强制，页面不重复判）/reject/revoke。审计事件
 * （installation.*）经服务调用自然落，本批不加新事件。
 *
 * <p><b>门控与安全边界</b>：与 MyAppsController 同——认证授权归部署方 default 链，控制器守"主体在池"；页面
 * 入口的 OWNER 门走 OrgService.isOwner（非 OWNER → A0508，与服务层同语义）。领域 bean 由 starter 供给，
 * 缺席（宿主未引 starter）渲染不支持提示，JSON 回 A0504。
 *
 * @author oatelauser
 */
@Controller
public class OrgInstallationsController {

    /** 审批页视图名（selfservice 命名空间模板，本模块视图解析器按白名单认领；自动配置读取）。 */
    public static final String VIEW_ORG_INSTALLATIONS = "org-installations";

    private final @Nullable OrgService orgService;

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
    public OrgInstallationsController(
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
     * 安装审批页：PENDING 待批区 + 全量安装行 + 发起表单（仅 OWNER，入口门见类注释）。
     *
     * @param orgId 路径 org id
     * @param principal 当前登录主体
     * @param model 视图模型
     * @return 视图名
     */
    @GetMapping("/selfservice/orgs/{orgId}/installations")
    public String page(@PathVariable("orgId") String orgId, @Nullable Principal principal, Model model) {
        model.addAttribute("educational", this.educational.enabled());
        boolean supported = domainBeansPresent();
        model.addAttribute("installationsSupported", supported);
        if (!supported) {
            return VIEW_ORG_INSTALLATIONS;
        }
        JauthUser user = requireUser(principal);
        requireOwner(orgId, user);
        Org org = requireOrg(orgId);
        List<InstallationRow> rows =
                installationRows(orgId, this.installationRepository, this.clientRepository, this.userRepository);
        model.addAttribute("org", org);
        model.addAttribute(
                "pending",
                rows.stream()
                        .filter(row -> row.status() == InstallationStatus.PENDING)
                        .toList());
        model.addAttribute("installations", rows);
        model.addAttribute(
                "scopes", scopeItems(LocaleContextHolder.getLocale(), this.scopeCatalog, this.messageSource));
        return VIEW_ORG_INSTALLATIONS;
    }

    /**
     * 发起安装 JSON（InstallationService.request）：任何登录用户可发起，requestedBy=当前用户；装进哪个 org
     * 就是路径里的 orgId。表单手填对外 client_id，控制器换算成框架 registered id 再交服务。
     *
     * @param orgId 目标 org id
     * @param request 发起请求（对外 client_id + 勾选 scopes）
     * @param principal 当前登录主体
     * @return SPI 渲染的成功体（data.installationId/data.status）
     */
    @PostMapping(
            value = "/selfservice/orgs/{orgId}/installations",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Object request(
            @PathVariable("orgId") String orgId, @RequestBody RequestRequest request, @Nullable Principal principal) {
        InstallationService service = requireService();
        JauthUser user = requireUser(principal);
        String clientId = request.clientId() == null ? "" : request.clientId().trim();
        if (clientId.isEmpty()) {
            throw new JauthException(JauthErrorCode.A0501);
        }
        Set<String> scopes = validatedScopes(request.scopes());
        RegisteredClient client = this.clientRepository.findByClientId(clientId);
        if (client == null) {
            throw new JauthException(JauthErrorCode.B0502);
        }
        Installation installation = service.request(client.getId(), orgId, scopes, user.id());
        return this.responseRenderer.renderSuccess(Map.of(
                "installationId",
                installation.id(),
                "status",
                installation.status().name()));
    }

    /**
     * 批准 JSON：勾选集即 ceilingScopes。空勾选先拒（A0511——批准即封顶，空集无意义；要拒绝走 reject 动作）；
     * ⊆ requested（A0502）、OWNER 门（A0508）、PENDING 状态门（A0507）由服务层强制。
     *
     * @param orgId 路径 org id（URL 结构位；真实门在安装行所属 org 的 OWNER，服务层判定）
     * @param installationId 安装 id
     * @param request 批准请求（勾选 ceiling scopes）
     * @param principal 当前登录主体
     * @return SPI 渲染的成功体（data.ceilingScopes）
     */
    @PostMapping(
            value = "/selfservice/orgs/{orgId}/installations/{installationId}/approve",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Object approve(
            @PathVariable("orgId") String orgId,
            @PathVariable("installationId") String installationId,
            @RequestBody ApproveRequest request,
            @Nullable Principal principal) {
        InstallationService service = requireService();
        JauthUser user = requireUser(principal);
        Set<String> ceilingScopes = request.scopes();
        if (ceilingScopes.isEmpty()) {
            throw new JauthException(SelfServiceErrorCode.A0511);
        }
        Installation approved = service.approve(installationId, user.id(), ceilingScopes);
        return this.responseRenderer.renderSuccess(Map.of("ceilingScopes", approved.ceilingScopes()));
    }

    /**
     * 驳回 JSON（PENDING → REJECTED）。
     *
     * @param orgId 路径 org id
     * @param installationId 安装 id
     * @param principal 当前登录主体
     * @return SPI 渲染的成功体（data 为 null）
     */
    @PostMapping(
            value = "/selfservice/orgs/{orgId}/installations/{installationId}/reject",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Object reject(
            @PathVariable("orgId") String orgId,
            @PathVariable("installationId") String installationId,
            @Nullable Principal principal) {
        requireService().reject(installationId, requireUser(principal).id());
        return this.responseRenderer.renderSuccess(null);
    }

    /**
     * 撤销 JSON（APPROVED → REVOKED，APPROVED 行显示撤销钮）。
     *
     * @param orgId 路径 org id
     * @param installationId 安装 id
     * @param principal 当前登录主体
     * @return SPI 渲染的成功体（data 为 null）
     */
    @PostMapping(
            value = "/selfservice/orgs/{orgId}/installations/{installationId}/revoke",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Object revoke(
            @PathVariable("orgId") String orgId,
            @PathVariable("installationId") String installationId,
            @Nullable Principal principal) {
        requireService().revoke(installationId, requireUser(principal).id());
        return this.responseRenderer.renderSuccess(null);
    }

    /** 行装配包内共径（v1.5 B1b：SSR 页与 JSON 状态面同源，提为 static——不复制行装配逻辑）。 */
    static List<InstallationRow> installationRows(
            String orgId,
            InstallationRepository installationRepository,
            RegisteredClientRepository clientRepository,
            UserRepository userRepository) {
        // 逐行反查 client/用户是循环内 DB 查询（p3c 强制项，B9 OrgScopeGate 同款口径）：
        // 按页记忆化——审批页行数是单 org 安装量，不值得为它引入批量查询面
        Map<String, RegisteredClient> clientsById = new HashMap<>();
        Map<String, JauthUser> usersById = new HashMap<>();
        List<InstallationRow> rows = new ArrayList<>();
        for (Installation installation : installationRepository.findByOrg(orgId)) {
            RegisteredClient client =
                    clientsById.computeIfAbsent(installation.registeredClientId(), clientRepository::findById);
            JauthUser requester = resolveUser(installation.requestedBy(), usersById, userRepository);
            JauthUser approver = resolveUser(installation.approvedBy(), usersById, userRepository);
            rows.add(new InstallationRow(
                    installation.id(),
                    client != null ? client.getClientName() : installation.registeredClientId(),
                    client != null ? client.getClientId() : installation.registeredClientId(),
                    installation.status(),
                    sorted(installation.ceilingScopes()),
                    displayNameOf(installation.requestedBy(), requester),
                    sorted(installation.requestedScopes()),
                    displayNameOf(installation.approvedBy(), approver),
                    installation.approvedAt(),
                    installation.createdAt()));
        }
        return List.copyOf(rows);
    }

    private static @Nullable JauthUser resolveUser(
            @Nullable String userId, Map<String, JauthUser> usersById, UserRepository userRepository) {
        return userId == null ? null : usersById.computeIfAbsent(userId, userRepository::findById);
    }

    /** scope 目录装配包内共径（v1.5 B1b；参数序照 {@code PatController.scopeItems}）。 */
    static List<ScopeItem> scopeItems(Locale locale, ScopeCatalog scopeCatalog, MessageSource messageSource) {
        List<ScopeItem> items = new ArrayList<>();
        for (ScopeDefinition definition : scopeCatalog.all()) {
            String description = messageSource.getMessage(definition.i18nKey(), null, definition.name(), locale);
            items.add(new ScopeItem(definition.name(), description));
        }
        return List.copyOf(items);
    }

    /** 守门：scope 勾选非空且全在目录内（信任边界，目录外的名字直接拒；语义同 PAT 创建，A0505 双面共用）。 */
    private Set<String> validatedScopes(Set<String> requested) {
        if (requested.isEmpty()) {
            throw new JauthException(SelfServiceErrorCode.A0505);
        }
        Set<String> known = new TreeSet<>();
        for (ScopeDefinition definition : this.scopeCatalog.all()) {
            known.add(definition.name());
        }
        if (!known.containsAll(requested)) {
            throw new JauthException(SelfServiceErrorCode.A0505);
        }
        return Set.copyOf(requested);
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

    private InstallationService requireService() {
        if (this.installationService == null || !domainBeansPresent()) {
            throw new JauthException(SelfServiceErrorCode.A0504);
        }
        return this.installationService;
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

    /** 展示名：displayName 优先，缺省回落 username；用户已删/不在池回退 id 片段，页面不炸。 */
    private static @Nullable String displayNameOf(@Nullable String userId, @Nullable JauthUser user) {
        if (userId == null) {
            return null;
        }
        if (user == null) {
            return userId.length() <= 12 ? userId : userId.substring(0, 12) + "…";
        }
        return user.displayName() == null || user.displayName().isBlank() ? user.username() : user.displayName();
    }

    private static List<String> sorted(Set<String> scopes) {
        return new ArrayList<>(new TreeSet<>(scopes));
    }

    /** scope 表单项（页面勾选模型，照 PatController 同名记录）。 */
    public record ScopeItem(String name, String description) {}

    /** 审批页行视图：secret/内部 id 不出域外，仅携带渲染所需列。 */
    public record InstallationRow(
            String id,
            String clientName,
            String clientLabel,
            InstallationStatus status,
            List<String> ceilingScopes,
            @Nullable String requestedByName,
            List<String> requestedScopes,
            @Nullable String approvedByName,
            @Nullable Instant approvedAt,
            Instant createdAt) {

        /** 防御性拷贝（SpotBugs EI_EXPOSE_REP：出入均不可变）。 */
        public InstallationRow {
            ceilingScopes = List.copyOf(ceilingScopes);
            requestedScopes = List.copyOf(requestedScopes);
        }

        /** 状态徽标的 i18n key（消息表达式不接受串拼接，动态 key 由服务端拼好交模板 `#{${...}}`）。 */
        public String statusKey() {
            return "jauth.orginst.status-" + this.status.name().toLowerCase(Locale.ROOT);
        }
    }

    /** 发起请求体：scopes 归一为不可空不可变集（缺失即空集，由校验路径拒绝）。 */
    public record RequestRequest(String clientId, Set<String> scopes) {

        /** 防御性拷贝（SpotBugs EI_EXPOSE_REP 双向）：JSON 反序列化的 Set 不透传引用。 */
        public RequestRequest {
            scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        }
    }

    /** 批准请求体：勾选的 ceiling scopes（空集由控制器按 A0511 先拒）。 */
    public record ApproveRequest(Set<String> scopes) {

        /** 防御性拷贝 + 归一（同 {@link RequestRequest}）。 */
        public ApproveRequest {
            scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        }
    }
}
