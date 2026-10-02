package io.github.oatelauser.jauth.selfservice.web;

import io.github.oatelauser.jauth.core.ratelimit.RateLimiter;
import io.github.oatelauser.jauth.core.response.JauthErrorCode;
import io.github.oatelauser.jauth.core.response.JauthException;
import io.github.oatelauser.jauth.core.response.ResponseRenderer;
import io.github.oatelauser.jauth.core.scope.ScopeCatalog;
import io.github.oatelauser.jauth.core.scope.ScopeDefinition;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import io.github.oatelauser.jauth.selfservice.pat.PatRecord;
import io.github.oatelauser.jauth.selfservice.pat.PatService;
import io.github.oatelauser.jauth.selfservice.pat.PatService.PatIssuance;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * PAT 管理页 + JSON 面（SPEC §2 selfservice 职责、§6 TTL 阶梯）。
 *
 * <p><b>页面</b>：SSR 渲染列表与创建表单（scope 勾选来自 {@link ScopeCatalog}，有效期 30/90/365 天默认 90）；
 * 创建/吊销由页内少量原生 JS 走 JSON 端点——明文令牌只在创建响应出现一次，直接渲染进页面的"仅此一次"横幅。
 *
 * <p><b>安全边界</b>：路径不在 jauth 协议链认领清单内（SPEC §2 宿主链共存规则），认证与授权由部署方的 default
 * 链负责；本控制器只守"主体可用"（principal 非空且在 jauth 用户池）。
 *
 * <p><b>memory 门控</b>（04 票）：{@link PatService} 仅 jdbc 装配，缺失时页面渲染"当前存储模式不支持"提示（不
 * 500），JSON 端点回 A0504。
 *
 * @author oatelauser
 */
@Controller
public class PatController {

    /** 视图名（selfservice 命名空间模板，本模块视图解析器按此白名单认领；自动配置读取）。 */
    public static final String VIEW_PAT = "pat";

    /** 有效期阶梯（SPEC §6：90d 默认，可选 30/90/365）。 */
    static final Set<Integer> VALIDITY_DAYS = Set.of(30, 90, 365);

    static final int DEFAULT_VALIDITY_DAYS = 90;

    /** 名称上限（jauth_pat.name VARCHAR(100)，V7）。 */
    static final int NAME_MAX_LENGTH = 100;

    private final @Nullable PatService patService;

    private final ScopeCatalog scopeCatalog;

    private final UserRepository userRepository;

    private final MessageSource messageSource;

    private final EducationalFlag educational;

    private final ResponseRenderer responseRenderer;

    private final Clock clock;

    /** 创建节流（v1.3 D5 老账④）。 */
    private final RateLimiter rateLimiter;

    public PatController(
            @Nullable PatService patService,
            ScopeCatalog scopeCatalog,
            UserRepository userRepository,
            MessageSource messageSource,
            EducationalFlag educational,
            ResponseRenderer responseRenderer,
            Clock clock,
            RateLimiter rateLimiter) {
        this.patService = patService;
        this.scopeCatalog = scopeCatalog;
        this.userRepository = userRepository;
        this.messageSource = messageSource;
        this.educational = educational;
        this.responseRenderer = responseRenderer;
        this.clock = clock;
        this.rateLimiter = rateLimiter;
    }

    /**
     * PAT 管理页。
     *
     * @param principal 当前登录主体
     * @param model 视图模型
     * @return 视图名
     */
    @GetMapping("/selfservice/pat")
    public String page(@Nullable Principal principal, Model model) {
        model.addAttribute("educational", this.educational.enabled());
        model.addAttribute("patSupported", this.patService != null);
        if (this.patService == null) {
            return VIEW_PAT;
        }
        JauthUser user = requireUser(principal);
        model.addAttribute(
                "scopes", scopeItems(this.scopeCatalog, this.messageSource, LocaleContextHolder.getLocale()));
        model.addAttribute("validityDays", VALIDITY_DAYS.stream().sorted().toList());
        model.addAttribute("defaultValidityDays", DEFAULT_VALIDITY_DAYS);
        model.addAttribute("pats", this.patService.listActive(user.id()));
        model.addAttribute("now", this.clock.instant());
        return VIEW_PAT;
    }

    /**
     * 列表 JSON：明文令牌永不出现，仅前缀与元数据。
     *
     * @param principal 当前登录主体
     * @return SPI 渲染的成功体
     */
    @GetMapping(value = "/selfservice/pat/list", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Object list(@Nullable Principal principal) {
        PatService service = requireService();
        JauthUser user = requireUser(principal);
        Instant now = this.clock.instant();
        List<Map<String, Object>> views = new ArrayList<>();
        for (PatRecord record : service.listActive(user.id())) {
            views.add(patView(record, now));
        }
        return this.responseRenderer.renderSuccess(views);
    }

    /**
     * 创建 JSON：唯一一次回显明文令牌。
     *
     * @param request 创建请求（名称 + scope 集 + 有效期天数）
     * @param principal 当前登录主体
     * @return SPI 渲染的成功体（data.token 为明文，仅此一次）
     */
    @PostMapping(value = "/selfservice/pat", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Object create(@RequestBody CreateRequest request, @Nullable Principal principal) {
        PatService service = requireService();
        JauthUser user = requireUser(principal);
        if (!this.rateLimiter.consume("create-pat:" + user.id()).allowed()) {
            // 创建节流（v1.3 D5 老账④）：每主体小时窗（共享限流器配额，键前缀区分 PAT 面）
            throw new JauthException(SelfServiceErrorCode.A0519);
        }
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty() || name.length() > NAME_MAX_LENGTH) {
            // 名称必填（V7 列，B10 起创建面强制）；缺参走 A0501
            throw new JauthException(JauthErrorCode.A0501);
        }
        Set<String> scopes = validatedScopes(request.scopes());
        int validityDays = request.validityDays() == null ? DEFAULT_VALIDITY_DAYS : request.validityDays();
        if (!VALIDITY_DAYS.contains(validityDays)) {
            throw new JauthException(JauthErrorCode.A0502);
        }
        PatIssuance issuance = service.create(user.id(), name, scopes, Duration.ofDays(validityDays));
        Map<String, Object> data = new LinkedHashMap<>(8);
        data.put("name", issuance.record().name());
        data.put("token", issuance.plaintextToken());
        data.put("prefix", issuance.record().tokenPrefix());
        data.put("scopes", issuance.record().scopes());
        data.put("expiresAt", issuance.record().expiresAt().toString());
        return this.responseRenderer.renderSuccess(data);
    }

    /**
     * 吊销 JSON。
     *
     * @param patId 记录 id
     * @param principal 当前登录主体
     * @return SPI 渲染的成功体（data 为 null）
     */
    @PostMapping("/selfservice/pat/{id}/revoke")
    @ResponseBody
    public Object revoke(@PathVariable("id") String patId, @Nullable Principal principal) {
        PatService service = requireService();
        JauthUser user = requireUser(principal);
        service.revoke(user.id(), patId);
        return this.responseRenderer.renderSuccess(null);
    }

    private PatService requireService() {
        if (this.patService == null) {
            throw new JauthException(SelfServiceErrorCode.A0504);
        }
        return this.patService;
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

    /** 守门：scope 勾选非空且全在目录内（信任边界校验，目录外的名字直接拒；请求体已归一为非 null 集）。 */
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
        return requested;
    }

    /** scope 表单项（名称 + i18n 描述），照 ConsentController 的目录展示契约。包内共径（v1.5 B1a：SSR 页与 JSON 状态面同源，scope 描述保持服务端解析——B3 决议）。 */
    static List<ScopeItem> scopeItems(ScopeCatalog scopeCatalog, MessageSource messageSource, Locale locale) {
        List<ScopeItem> items = new ArrayList<>();
        for (ScopeDefinition definition : scopeCatalog.all()) {
            String description = messageSource.getMessage(definition.i18nKey(), null, definition.name(), locale);
            items.add(new ScopeItem(definition.name(), description));
        }
        return items;
    }

    /** PAT 行视图包内共径（v1.5 B1a：/list 与 JSON 状态面同源；明文令牌永不出现）。 */
    static Map<String, Object> patView(PatRecord record, Instant now) {
        Map<String, Object> view = new LinkedHashMap<>(8);
        view.put("id", record.id());
        view.put("name", record.name());
        view.put("prefix", record.tokenPrefix());
        view.put("scopes", record.scopes());
        view.put("status", record.status().name());
        view.put("expired", record.expired(now));
        view.put("createdAt", record.createdAt().toString());
        view.put("expiresAt", record.expiresAt().toString());
        view.put(
                "lastUsedAt",
                record.lastUsedAt() == null ? null : record.lastUsedAt().toString());
        return view;
    }

    /** 创建请求体：scopes 归一为不可空不可变集（缺失即空集，由校验路径拒绝）。 */
    public record CreateRequest(String name, Set<String> scopes, Integer validityDays) {

        /** 防御性拷贝（SpotBugs EI_EXPOSE_REP 双向）：JSON 反序列化的 Set 不透传引用。 */
        public CreateRequest {
            scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        }
    }

    /** scope 表单项（页面勾选模型）。 */
    public record ScopeItem(String name, String description) {}
}
