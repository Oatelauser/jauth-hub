package io.github.oatelauser.jauth.core.web;

import io.github.oatelauser.jauth.core.authorization.CeilingAwareOAuth2AuthorizationService;
import io.github.oatelauser.jauth.core.client.ClientOwner;
import io.github.oatelauser.jauth.core.client.ClientOwnerResolver;
import io.github.oatelauser.jauth.core.org.OrgMembership;
import io.github.oatelauser.jauth.core.org.OrgScopeGate;
import io.github.oatelauser.jauth.core.scope.ScopeCatalog;
import io.github.oatelauser.jauth.core.scope.ScopeDefinition;
import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * consent 页状态装配器（v1.4 B1）：org 三态判定 + scopeItems + grantedScopes + clientName 解析 +
 * <b>org 选择的会话暂存副作用</b>的唯一持有处——SSR 页（{@link ConsentController}）与 JSON 状态面
 * （{@link ConsentStateController}）共用同一装配路径，保证 headless 皮与 SSR 皮逐字段同源（SPEC §1 混合用法）。
 *
 * <p><b>组织客户端的 org 上下文三态</b>（B9，口径与强制单点在 {@link OrgScopeGate} 与 CeilingAware 装饰器——渲染层只是
 * UX，服务端剪枝才是防线）：个人/平台客户端渲染完全不变；组织客户端按候选 org 集分三态——0 个渲染引导态（需 org OWNER
 * 安装）、1 个隐式上下文 + 会话暂存 + 徽标展示、多个渲染链接式选择器（GET 带 org 参数重入，保留原有
 * client_id/state/scope 参数；合法选择后写会话暂存并重渲染）。org 上下文经会话暂存
 * {@value CeilingAwareOAuth2AuthorizationService#CONSENT_ORG_SESSION_KEY_PREFIX}&lt;state&gt; 传给服务端装饰器，<b>不经表单字段</b>——
 * POST 契约（client_id/state/scope）保持不变。选定后 ceiling 内的 scope 正常勾选，requested 但超出 ceiling 的展示但禁用（教学面：看得见批不下来）。
 *
 * @author oatelauser
 */
public class ConsentPageAssembler {

    /**
     * scope 表单项：目录未收录的 scope 也如实展示，描述回退为 scope 名本身；grantable=false 渲染为禁用（超出
     * ceiling）；alreadyGranted=true 为既有授权（v1.3 D4 老账⑥：默认勾选 + 「已授权」徽标，勾选语义不变——
     * consent 重录需要完整勾选集，徽标只做增量授权的知情区分）。
     */
    public record ScopeItem(
            String name, String description, boolean checked, boolean grantable, boolean alreadyGranted) {}

    /** org 选择器条目：orgId 供选中标识，href 为保留 client_id/state/scope 的 SSR 页重入链接。 */
    public record OrgChoice(String orgId, String orgName, String href) {}

    /** 装配结果：组件名即 SSR 视图模型属性名，JSON 状态面照同名字段输出。 */
    public record ConsentPageModel(
            String clientId,
            String state,
            String clientName,
            boolean educational,
            boolean orgGuide,
            List<OrgChoice> orgChoices,
            @Nullable String orgBadge,
            List<ScopeItem> scopes) {

        /** 集合防御性拷贝为不可变表（SpotBugs EI_EXPOSE_REP：PatRecord 同款取舍，出入均不可再被外部改动）。 */
        public ConsentPageModel {
            orgChoices = List.copyOf(orgChoices);
            scopes = List.copyOf(scopes);
        }
    }

    /** org 上下文渲染态：plain（个人/平台）、guide、select（choices 非空）、selected（badge + ceiling）。 */
    private record OrgConsentView(
            boolean guide,
            List<OrgChoice> choices,
            @Nullable String selectedOrgName,
            @Nullable Set<String> ceilingScopes) {

        static OrgConsentView plain() {
            return new OrgConsentView(false, List.of(), null, null);
        }

        static OrgConsentView guideView() {
            return new OrgConsentView(true, List.of(), null, null);
        }
    }

    private final RegisteredClientRepository clientRepository;

    private final ScopeCatalog scopeCatalog;

    private final MessageSource messageSource;

    private final EducationalFlag educational;

    private final OrgScopeGate orgScopeGate;

    private final ClientOwnerResolver clientOwnerResolver;

    private final OAuth2AuthorizationConsentService consentService;

    /**
     * EI_EXPOSE_REP2 定向豁免：consent 服务是容器单例门面（Spring 注入通行形态，构造后无可变面暴露）。
     */
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(value = "EI_EXPOSE_REP2")
    public ConsentPageAssembler(
            RegisteredClientRepository clientRepository,
            ScopeCatalog scopeCatalog,
            MessageSource messageSource,
            EducationalFlag educational,
            OrgScopeGate orgScopeGate,
            ClientOwnerResolver clientOwnerResolver,
            OAuth2AuthorizationConsentService consentService) {
        this.clientRepository = clientRepository;
        this.scopeCatalog = scopeCatalog;
        this.messageSource = messageSource;
        this.educational = educational;
        this.orgScopeGate = orgScopeGate;
        this.clientOwnerResolver = clientOwnerResolver;
        this.consentService = consentService;
    }

    /**
     * 装配 consent 页状态（参数契约与 SSR 页一致）。
     *
     * <p>框架重定向到 consent 页时 scope 是空格拼接的单值参数（SAS OAuth2AuthorizationEndpointFilter 以
     * {@code String.join(" ", scopes)} 拼查询串），MVC 对单值的集合绑定只按逗号切分，故自行按空白拆开，兼容单值与多值两种形态。
     *
     * @param clientId 框架重定向携带的 client_id 参数
     * @param state 框架重定向携带的 state 参数（防 CSRF，表单回传）
     * @param scopeParams 框架重定向携带的 scope 参数（空格拼接单值或多值）
     * @param orgParam org 选择器的重入参数（多候选时指定所选 org，须在候选集内）
     * @param principal 当前登录主体（consent 页必在认证后到达；缺席按无 org 上下文装配）
     * @param session 会话（org 选择的暂存载体，副作用与 SSR 完全同路径）
     * @return 页面状态模型
     */
    public ConsentPageModel assemble(
            String clientId,
            String state,
            List<String> scopeParams,
            @Nullable String orgParam,
            @Nullable Authentication principal,
            HttpSession session) {
        Set<String> requestedScopes = splitScopes(scopeParams);
        OrgConsentView orgView = orgConsentView(clientId, state, requestedScopes, orgParam, principal, session);
        return new ConsentPageModel(
                clientId,
                state,
                resolveClientName(clientId),
                educational.enabled(),
                orgView.guide(),
                orgView.choices(),
                orgView.selectedOrgName(),
                scopeItems(
                        requestedScopes,
                        grantedScopes(clientId, principal),
                        orgView.ceilingScopes(),
                        LocaleContextHolder.getLocale()));
    }

    private Set<String> splitScopes(List<String> scopeParams) {
        return scopeParams.stream()
                .flatMap(param -> Arrays.stream(param.trim().split("\\s+")))
                .collect(Collectors.toSet());
    }

    private String resolveClientName(String clientId) {
        RegisteredClient client = clientRepository.findById(clientId);
        // 查无此 client（已被删除等）仍装配页面，展示 client_id 本身——consent 判定由框架侧做
        return client != null && client.getClientName() != null ? client.getClientName() : clientId;
    }

    /** scope 项：ceiling 为 null（个人/平台或未选定 org）全部可勾选；组织客户端选定后 ceiling 内勾选、超界禁用。 */
    private List<ScopeItem> scopeItems(
            Set<String> scopes, Set<String> granted, @Nullable Set<String> ceiling, Locale locale) {
        List<ScopeItem> items = new ArrayList<>(scopes.size());
        for (String name : scopes) {
            boolean grantable = ceiling == null || ceiling.contains(name);
            items.add(new ScopeItem(name, description(name, locale), grantable, grantable, granted.contains(name)));
        }
        items.sort(Comparator.comparing(ScopeItem::name));
        return items;
    }

    /**
     * 该用户在该 client 上的既有授权 scope（v1.3 D4 老账⑥）：consent 行查询（框架服务口径 =
     * registered_client_id 内部 id + principal 名）；无行/无主体返回空集。
     */
    private Set<String> grantedScopes(String clientId, @Nullable Authentication principal) {
        if (principal == null) {
            return Set.of();
        }
        RegisteredClient client = clientRepository.findByClientId(clientId);
        if (client == null) {
            return Set.of();
        }
        OAuth2AuthorizationConsent consent = consentService.findById(client.getId(), principal.getName());
        return consent == null ? Set.of() : consent.getScopes();
    }

    /**
     * 描述三级兜底序（v1.2 C4 ③）：i18n key 命中 > 注解 desc（fallbackDesc，{@code @RequiresScope} 流入）>
     * 裸名。目录外 scope 仍如实展示裸名（渲染层不拒，判定归框架与勾选守门面）。
     */
    private String description(String name, Locale locale) {
        ScopeDefinition definition = scopeCatalog.find(name).orElse(null);
        if (definition == null) {
            return name;
        }
        String fallback = definition.fallbackDesc() != null ? definition.fallbackDesc() : name;
        return messageSource.getMessage(definition.i18nKey(), null, fallback, locale);
    }

    /**
     * org 上下文三态判定：个人/平台（或未认证主体）→ plain；组织客户端按候选集 → guide/select/selected
     * （1 候选与合法 org 参数选择均写会话暂存，供服务端装饰器在 code 生成保存时读取）。
     */
    private OrgConsentView orgConsentView(
            String clientId,
            String state,
            Set<String> requestedScopes,
            @Nullable String orgParam,
            @Nullable Authentication principal,
            HttpSession session) {
        if (principal == null) {
            return OrgConsentView.plain();
        }
        RegisteredClient client = clientRepository.findByClientId(clientId);
        ClientOwner owner = client == null ? null : clientOwnerResolver.findOwner(client.getId());
        if (owner == null || owner.orgId() == null) {
            return OrgConsentView.plain();
        }
        List<OrgMembership> candidates = orgScopeGate.approvedOrgs(principal.getName(), client.getId());
        if (candidates.isEmpty()) {
            return OrgConsentView.guideView();
        }
        OrgMembership selected = selectedCandidate(candidates, orgParam);
        if (selected == null && candidates.size() == 1) {
            selected = candidates.get(0);
        }
        return selected != null
                ? selectedView(client, state, selected, session)
                : selectorView(clientId, state, requestedScopes, candidates);
    }

    private OrgConsentView selectorView(
            String clientId, String state, Set<String> requestedScopes, List<OrgMembership> candidates) {
        List<OrgChoice> choices = candidates.stream()
                .map(membership -> new OrgChoice(
                        membership.orgId(),
                        membership.orgName(),
                        choiceHref(clientId, state, requestedScopes, membership.orgId())))
                .sorted(Comparator.comparing(OrgChoice::orgName))
                .toList();
        return new OrgConsentView(false, choices, null, null);
    }

    /** 合法 org 参数（在候选集内）优先；缺席或非法返回 null 交由候选数分流。 */
    private @Nullable OrgMembership selectedCandidate(List<OrgMembership> candidates, @Nullable String orgParam) {
        if (orgParam == null) {
            return null;
        }
        return candidates.stream()
                .filter(membership -> membership.orgId().equals(orgParam))
                .findFirst()
                .orElse(null);
    }

    private OrgConsentView selectedView(
            RegisteredClient client, String state, OrgMembership selected, HttpSession session) {
        Set<String> ceiling = orgScopeGate.ceilingScopes(client.getId(), selected.orgId());
        if (ceiling == null) {
            // 渲染与封门读取之间安装被撤销的竞态：按引导态渲染，服务端装饰器仍 fail-closed
            return OrgConsentView.guideView();
        }
        session.setAttribute(
                CeilingAwareOAuth2AuthorizationService.CONSENT_ORG_SESSION_KEY_PREFIX + state, selected.orgId());
        return new OrgConsentView(false, List.of(), selected.orgName(), ceiling);
    }

    /** 选择器重入链接：保留框架带来的 client_id/state/scope 原参数，追加所选 org。 */
    private String choiceHref(String clientId, String state, Set<String> requestedScopes, String orgId) {
        return UriComponentsBuilder.fromPath("/oauth2/consent")
                .queryParam("client_id", clientId)
                .queryParam("state", state)
                .queryParam("scope", String.join(" ", requestedScopes))
                .queryParam("org", orgId)
                .encode()
                .build()
                .toUriString();
    }
}
