package io.github.oatelauser.jauth.core.web;

import io.github.oatelauser.jauth.core.scope.ScopeCatalog;
import io.github.oatelauser.jauth.core.scope.ScopeDefinition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Consent 页：授权码流程第二步，scope 交集在此成形（05 票教学一句话）。
 *
 * <p>照 SAS 官方 consent 页示例模式：框架在需要授权时重定向到本页并携带 client_id/state/scope 请求参数，表单原样回传； 勾选 scope
 * 与发行令牌取交集的语义由 B4 装配的授权流程落地，本控制器只把视图与表单模型组装正确。 勾选态本批默认全选（框架示例行为：请求的 scope 默认勾上）；"已授权过的默认勾选"需接
 * consent 存储，随 B4 细化。
 *
 * @author oatelauser
 */
@Controller
public class ConsentController {

    /** 视图名：模板位于 core 命名空间 templates 目录，解析前缀由装配方（B4）配置。 */
    static final String VIEW_CONSENT = "consent";

    /** scope 表单项：目录未收录的 scope 也如实展示，描述回退为 scope 名本身。 */
    public record ScopeItem(String name, String description, boolean checked) {}

    private final RegisteredClientRepository clientRepository;

    private final ScopeCatalog scopeCatalog;

    private final MessageSource messageSource;

    private final EducationalFlag educational;

    public ConsentController(
            RegisteredClientRepository clientRepository,
            ScopeCatalog scopeCatalog,
            MessageSource messageSource,
            EducationalFlag educational) {
        this.clientRepository = clientRepository;
        this.scopeCatalog = scopeCatalog;
        this.messageSource = messageSource;
        this.educational = educational;
    }

    /**
     * 渲染授权确认页。
     *
     * <p>框架重定向到本页时 scope 是空格拼接的单值参数（SAS OAuth2AuthorizationEndpointFilter 以 {@code String.join(" ",
     * scopes)} 拼查询串），MVC 对单值的集合绑定只按逗号切分，故此处自行按空白拆开，兼容单值与多值两种形态。
     *
     * @param clientId 框架重定向携带的 client_id 参数
     * @param state 框架重定向携带的 state 参数（防 CSRF，表单回传）
     * @param scopeParams 框架重定向携带的 scope 参数（空格拼接单值或多值）
     * @param model 视图模型
     * @return 视图名
     */
    @GetMapping("/oauth2/consent")
    public String consent(
            @RequestParam("client_id") String clientId,
            @RequestParam("state") String state,
            @RequestParam("scope") List<String> scopeParams,
            Model model) {
        model.addAttribute("clientId", clientId);
        model.addAttribute("state", state);
        model.addAttribute("clientName", resolveClientName(clientId));
        model.addAttribute("scopes", scopeItems(splitScopes(scopeParams), LocaleContextHolder.getLocale()));
        model.addAttribute("educational", educational.enabled());
        return VIEW_CONSENT;
    }

    private Set<String> splitScopes(List<String> scopeParams) {
        return scopeParams.stream()
                .flatMap(param -> Arrays.stream(param.trim().split("\\s+")))
                .collect(Collectors.toSet());
    }

    private String resolveClientName(String clientId) {
        RegisteredClient client = clientRepository.findById(clientId);
        // 查无此 client（已被删除等）仍渲染页面，展示 client_id 本身——consent 判定由框架侧做
        return client != null && client.getClientName() != null ? client.getClientName() : clientId;
    }

    private List<ScopeItem> scopeItems(Set<String> scopes, Locale locale) {
        List<ScopeItem> items = new ArrayList<>(scopes.size());
        for (String name : scopes) {
            ScopeDefinition definition = scopeCatalog.find(name).orElse(null);
            String description =
                    definition != null ? messageSource.getMessage(definition.i18nKey(), null, name, locale) : name;
            items.add(new ScopeItem(name, description, true));
        }
        items.sort(Comparator.comparing(ScopeItem::name));
        return items;
    }
}
