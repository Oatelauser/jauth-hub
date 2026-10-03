package io.github.oatelauser.jauth.selfservice.web;

import io.github.oatelauser.jauth.core.response.JauthErrorCode;
import io.github.oatelauser.jauth.core.response.JauthException;
import io.github.oatelauser.jauth.core.response.ResponseRenderer;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 已授权应用看板：JSON 面（05 票 v1.0 页面：授权的持久化与撤销语义）；页面路由 v1.5 B5b 起 302 到
 * {@code /front/selfservice/apps} 的 SPA 皮（SSR 皮退场），查询串原样转发。
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

    private final @Nullable AuthorizedAppService appService;

    private final ObjectProvider<OAuth2AuthorizationService> authorizationService;

    private final ObjectProvider<OAuth2AuthorizationConsentService> consentService;

    private final RegisteredClientRepository clientRepository;

    private final ResponseRenderer responseRenderer;

    public AuthorizedAppsController(
            @Nullable AuthorizedAppService appService,
            ObjectProvider<OAuth2AuthorizationService> authorizationService,
            ObjectProvider<OAuth2AuthorizationConsentService> consentService,
            RegisteredClientRepository clientRepository,
            ResponseRenderer responseRenderer) {
        this.appService = appService;
        this.authorizationService = authorizationService;
        this.consentService = consentService;
        this.clientRepository = clientRepository;
        this.responseRenderer = responseRenderer;
    }

    /**
     * 看板页入口：302 到 SPA 皮。
     *
     * @param request 当前请求（查询串原样转发给 SPA）
     * @return 重定向指令
     */
    @GetMapping("/selfservice/apps")
    public String page(HttpServletRequest request) {
        return "redirect:" + SudoController.frontTarget("/front/selfservice/apps", request);
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
        return this.responseRenderer.renderSuccess(appViews(service.list(principal.getName()), this.clientRepository));
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

    /** 行装配包内共径（v1.5 B1a：list / JSON 状态面同源，提为 static——不复制行装配逻辑）。 */
    static List<AppView> appViews(List<AuthorizedApp> apps, RegisteredClientRepository clientRepository) {
        List<AppView> views = new ArrayList<>(apps.size());
        for (AuthorizedApp app : apps) {
            views.add(new AppView(
                    app.registeredClientId(),
                    resolveClientName(clientRepository, app.registeredClientId()),
                    new ArrayList<>(new TreeSet<>(app.scopes())),
                    app.lastAuthorizedAt()));
        }
        return views;
    }

    private static String resolveClientName(RegisteredClientRepository clientRepository, String registeredClientId) {
        RegisteredClient client = clientRepository.findById(registeredClientId);
        return client != null && client.getClientName() != null ? client.getClientName() : registeredClientId;
    }

    /** 看板行（list 与 JSON 共用投影）：scopes 有序化保证输出稳定。 */
    public record AppView(String clientId, String clientName, List<String> scopes, Instant lastAuthorizedAt) {

        /** scopes 排序拷贝（目录序不稳定，展示面要确定性）。 */
        public AppView {
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }
    }
}
