package io.github.oatelauser.jauth.selfservice.web;

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
 * 我的应用页 JSON 状态面（v1.5 B1a，路径族规约：页面路径加 {@code /api} 前缀）：{@code GET
 * /api/selfservice/my-apps} 与 SSR 页（{@link MyAppsController#page}）同口径——appsSupported = 服务在场性、
 * 非池内主体与服务缺席同态为空列表（页面不炸的取舍在 JSON 面收敛为空集），行 = {@link OwnedAppService.OwnedApp}
 * （SSR 页模型的直接复用，无二次装配）。my-app-new 页复用本端点（B0 普查决议，不设独立端点）。安全边界同
 * SSR 页（部署方 default 链，starter 链不动）。
 *
 * <p>B3 前端消费契约：{@code data:{ educational, appsSupported, apps, csrfToken, csrfHeaderName }}。
 *
 * @author oatelauser
 */
@RestController
public class MyAppsStateController {

    private final @Nullable OwnedAppService ownedAppService;

    private final UserRepository userRepository;

    private final EducationalFlag educational;

    private final ResponseRenderer responseRenderer;

    /**
     * EI_EXPOSE_REP2 定向豁免：OwnedAppService 是抽象类（SpotBugs 视可变表示），实为容器单例服务门面
     * （Spring 注入通行形态，构造后无可变面暴露——MyAppsController 同款豁免）。
     */
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(value = "EI_EXPOSE_REP2")
    public MyAppsStateController(
            @Nullable OwnedAppService ownedAppService,
            UserRepository userRepository,
            EducationalFlag educational,
            ResponseRenderer responseRenderer) {
        this.ownedAppService = ownedAppService;
        this.userRepository = userRepository;
        this.educational = educational;
        this.responseRenderer = responseRenderer;
    }

    /**
     * 我的应用页状态（my-app-new 注册页同源消费）。
     *
     * @param principal 当前登录主体（非池内主体即空列表态，同 SSR 页）
     * @param request 请求（取 CSRF 惰性请求属性）
     * @return 统一包装的页面状态
     */
    @GetMapping(value = "/api/selfservice/my-apps", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object state(@Nullable Principal principal, HttpServletRequest request) {
        List<OwnedAppService.OwnedApp> apps = List.of();
        JauthUser user = principal == null ? null : this.userRepository.findByUsername(principal.getName());
        if (this.ownedAppService != null && user != null) {
            apps = this.ownedAppService.list(user.id());
        }
        CsrfPayload csrf = CsrfPayload.from(request);
        return this.responseRenderer.renderSuccess(new MyAppsState(
                this.educational.enabled(),
                this.ownedAppService != null,
                apps,
                csrf.csrfToken(),
                csrf.csrfHeaderName()));
    }

    /** 我的应用页状态载荷（字段名即 B3 前端契约；OwnedApp 行与 SSR 页模型同形）。 */
    public record MyAppsState(
            boolean educational,
            boolean appsSupported,
            List<OwnedAppService.OwnedApp> apps,
            @Nullable String csrfToken,
            @Nullable String csrfHeaderName) {

        /** apps 防御性拷贝（SpotBugs EI_EXPOSE_REP：出入均不可变）。 */
        public MyAppsState {
            apps = apps == null ? List.of() : List.copyOf(apps);
        }
    }
}
