package io.github.oatelauser.jauth.selfservice.web;

import io.github.oatelauser.jauth.core.response.JauthErrorCode;
import io.github.oatelauser.jauth.core.response.JauthException;
import io.github.oatelauser.jauth.core.response.ResponseRenderer;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import io.github.oatelauser.jauth.core.web.PasskeyFlag;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 已授权应用看板页 + JSON 面（05 票 v1.0 页面：授权的持久化与撤销语义）。
 *
 * <p>页面 SSR 渲染聚合行（client 展示名经 {@link RegisteredClientRepository} 解析，查无回退 id）；"解除授权"由页内
 * 少量原生 JS 调 JSON 端点。"最近使用"列暂缺，B7 审计上线后补。
 *
 * <p><b>Revoke 编排在此而非查询服务</b>：双 remove = 该 (principal, client) 的全部授权
 * {@link OAuth2AuthorizationService#remove} + 授权许可 {@link OAuth2AuthorizationConsentService#remove}（不清
 * consent 的话，下次授权跳过确认页，"解除授权"名不副实）；两框架服务两模式都由 starter 装配，控制器持有一次编成，
 * {@link AuthorizedAppService} 保持只读。
 *
 * <p>安全边界与 memory 门控同 {@link PatController} 类注释。
 *
 * @author oatelauser
 */
@Controller
public class AuthorizedAppsController {

    /** 视图名（selfservice 命名空间模板，本模块视图解析器按此白名单认领；自动配置读取）。 */
    public static final String VIEW_APPS = "apps";

    private final @Nullable AuthorizedAppService appService;

    private final ObjectProvider<OAuth2AuthorizationService> authorizationService;

    private final ObjectProvider<OAuth2AuthorizationConsentService> consentService;

    private final RegisteredClientRepository clientRepository;

    private final EducationalFlag educational;

    private final PasskeyFlag passkey;

    private final ResponseRenderer responseRenderer;

    public AuthorizedAppsController(
            @Nullable AuthorizedAppService appService,
            ObjectProvider<OAuth2AuthorizationService> authorizationService,
            ObjectProvider<OAuth2AuthorizationConsentService> consentService,
            RegisteredClientRepository clientRepository,
            EducationalFlag educational,
            PasskeyFlag passkey,
            ResponseRenderer responseRenderer) {
        this.appService = appService;
        this.authorizationService = authorizationService;
        this.consentService = consentService;
        this.clientRepository = clientRepository;
        this.educational = educational;
        this.passkey = passkey;
        this.responseRenderer = responseRenderer;
    }

    /**
     * 看板页。
     *
     * @param principal 当前登录主体
     * @param model 视图模型
     * @return 视图名
     */
    @GetMapping("/selfservice/apps")
    public String page(@Nullable Principal principal, Model model) {
        model.addAttribute("educational", this.educational.enabled());
        model.addAttribute("appsSupported", this.appService != null);
        model.addAttribute("passkeyEnabled", this.passkey.enabled());
        if (this.appService == null || principal == null) {
            return VIEW_APPS;
        }
        model.addAttribute("apps", appViews(this.appService.list(principal.getName())));
        return VIEW_APPS;
    }

    /**
     * 列表 JSON。
     *
     * @param principal 当前登录主体
     * @return SPI 渲染的成功体
     */
    @GetMapping(value = "/selfservice/apps/list", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Object list(@Nullable Principal principal) {
        AuthorizedAppService service = requireService();
        requirePrincipal(principal);
        return this.responseRenderer.renderSuccess(appViews(service.list(principal.getName())));
    }

    /**
     * 一键 Revoke JSON：授权 + 授权许可双清。
     *
     * @param registeredClientId 客户端注册 id
     * @param principal 当前登录主体
     * @return SPI 渲染的成功体（data 为 null）
     */
    @PostMapping("/selfservice/apps/{clientId}/revoke")
    @ResponseBody
    public Object revoke(@PathVariable("clientId") String registeredClientId, @Nullable Principal principal) {
        AuthorizedAppService service = requireService();
        requirePrincipal(principal);
        revokeApp(principal.getName(), registeredClientId, service);
        return this.responseRenderer.renderSuccess(null);
    }

    /**
     * 双 remove 编排：授权行全清 + consent 删除；两腿皆空 = 无可撤销，B0502。
     *
     * <p>两框架服务经 {@link ObjectProvider} 按请求解析（starter 两模式都装配）：不在字段位持有可变服务接口，
     * 与全库"不把 OAuth2AuthorizationService 存成员"的既有形态一致（SpotBugs EI_EXPOSE_REP2 洁净）。
     */
    private void revokeApp(String principalName, String registeredClientId, AuthorizedAppService service) {
        OAuth2AuthorizationService authorizations = this.authorizationService.getObject();
        OAuth2AuthorizationConsentService consents = this.consentService.getObject();
        int removedAuthorizations = 0;
        for (String authorizationId : service.authorizationIds(principalName, registeredClientId)) {
            OAuth2Authorization authorization = authorizations.findById(authorizationId);
            if (authorization != null) {
                authorizations.remove(authorization);
                removedAuthorizations++;
            }
        }
        OAuth2AuthorizationConsent consent = consents.findById(registeredClientId, principalName);
        boolean removedConsent = false;
        if (consent != null) {
            consents.remove(consent);
            removedConsent = true;
        }
        if (removedAuthorizations == 0 && !removedConsent) {
            throw new JauthException(JauthErrorCode.B0502);
        }
    }

    private AuthorizedAppService requireService() {
        if (this.appService == null) {
            throw new JauthException(SelfServiceErrorCode.A0504);
        }
        return this.appService;
    }

    private void requirePrincipal(@Nullable Principal principal) {
        if (principal == null) {
            throw new JauthException(SelfServiceErrorCode.A0503);
        }
    }

    private List<AppView> appViews(List<AuthorizedApp> apps) {
        List<AppView> views = new ArrayList<>(apps.size());
        for (AuthorizedApp app : apps) {
            views.add(new AppView(
                    app.registeredClientId(),
                    resolveClientName(app.registeredClientId()),
                    new ArrayList<>(new TreeSet<>(app.scopes())),
                    app.lastAuthorizedAt()));
        }
        return views;
    }

    private String resolveClientName(String registeredClientId) {
        RegisteredClient client = this.clientRepository.findById(registeredClientId);
        return client != null && client.getClientName() != null ? client.getClientName() : registeredClientId;
    }

    /** 看板行（页面与 JSON 共用投影）：scopes 有序化保证页面/JSON 输出稳定。 */
    public record AppView(String clientId, String clientName, List<String> scopes, Instant lastAuthorizedAt) {

        /** scopes 排序拷贝（目录序不稳定，展示面要确定性）。 */
        public AppView {
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }
    }
}
