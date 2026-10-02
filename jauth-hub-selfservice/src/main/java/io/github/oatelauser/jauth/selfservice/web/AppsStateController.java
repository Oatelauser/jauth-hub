package io.github.oatelauser.jauth.selfservice.web;

import io.github.oatelauser.jauth.core.response.ResponseRenderer;
import io.github.oatelauser.jauth.core.web.CsrfPayload;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import io.github.oatelauser.jauth.core.web.PasskeyFlag;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 已授权应用看板页 JSON 状态面（v1.5 B1a，路径族规约：页面路径加 {@code /api} 前缀）：{@code GET
 * /api/selfservice/apps} 与 SSR 页（{@link AuthorizedAppsController#page}）同口径——appsSupported = 服务
 * 在场性（memory 模式 false）、apps 行装配复用 {@link AuthorizedAppsController#appViews}（与既有
 * {@code /selfservice/apps/list} 同形同源，不复制行装配逻辑）。安全边界同 SSR 页：路径不在 jauth 协议链
 * 认领清单内，认证由部署方 default 链负责（starter 链不动）。
 *
 * <p>B3 前端消费契约：{@code data:{ educational, appsSupported, passkeyEnabled, apps, csrfToken,
 * csrfHeaderName }}；CSRF 字段可空语义见 {@link CsrfPayload}。apps 字段恒在场（服务缺席/未认证时空列表，
 * 供前端类型稳定分支——SSR 模板属性缺席语义在 JSON 面收敛为空集）。
 *
 * @author oatelauser
 */
@RestController
public class AppsStateController {

    private final @Nullable AuthorizedAppService appService;

    private final RegisteredClientRepository clientRepository;

    private final EducationalFlag educational;

    private final PasskeyFlag passkey;

    private final ResponseRenderer responseRenderer;

    public AppsStateController(
            @Nullable AuthorizedAppService appService,
            RegisteredClientRepository clientRepository,
            EducationalFlag educational,
            PasskeyFlag passkey,
            ResponseRenderer responseRenderer) {
        this.appService = appService;
        this.clientRepository = clientRepository;
        this.educational = educational;
        this.passkey = passkey;
        this.responseRenderer = responseRenderer;
    }

    /**
     * 看板页状态。
     *
     * @param principal 当前登录主体（缺席即空列表态，同 SSR 页）
     * @param request 请求（取 CSRF 惰性请求属性）
     * @return 统一包装的页面状态
     */
    @GetMapping(value = "/api/selfservice/apps", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object state(@Nullable Principal principal, HttpServletRequest request) {
        List<AuthorizedAppsController.AppView> apps = List.of();
        if (this.appService != null && principal != null) {
            apps = AuthorizedAppsController.appViews(this.appService.list(principal.getName()), this.clientRepository);
        }
        CsrfPayload csrf = CsrfPayload.from(request);
        return this.responseRenderer.renderSuccess(new AppsState(
                this.educational.enabled(),
                this.appService != null,
                this.passkey.enabled(),
                apps,
                csrf.csrfToken(),
                csrf.csrfHeaderName()));
    }

    /** 看板页状态载荷（字段名即 B3 前端契约；AppView 行与既有 /list 同形）。 */
    public record AppsState(
            boolean educational,
            boolean appsSupported,
            boolean passkeyEnabled,
            List<AuthorizedAppsController.AppView> apps,
            @Nullable String csrfToken,
            @Nullable String csrfHeaderName) {

        /** apps 防御性拷贝（SpotBugs EI_EXPOSE_REP：出入均不可变）。 */
        public AppsState {
            apps = apps == null ? List.of() : List.copyOf(apps);
        }
    }
}
