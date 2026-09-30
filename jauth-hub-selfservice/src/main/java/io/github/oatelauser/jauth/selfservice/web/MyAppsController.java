package io.github.oatelauser.jauth.selfservice.web;

import io.github.oatelauser.jauth.core.response.JauthException;
import io.github.oatelauser.jauth.core.response.ResponseRenderer;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 我的应用页（B10：个人应用免安装审批的自助注册面，SPEC §3/§7 v1.1 应用管理页）。
 *
 * <p><b>页面</b>：列表（GET /selfservice/my-apps）与注册表单（GET /selfservice/my-apps/new，最小三件：名称/redirect URIs/类型）；
 * 注册走页内原生 JS 调 JSON 端点（照 PAT 页惯例）——机密应用的 client_secret 明文只在注册响应出现一次，
 * 直接渲染进"仅此一次"横幅。scopes 不进表单：allowed scopes = scope 目录全集，封顶由 consent 页把关
 * （个人应用无 ceiling）。
 *
 * <p><b>门控与安全边界</b>：与 PatController 同——路径不在 jauth 协议链认领清单内，认证授权归部署方 default 链；
 * 控制器守"主体在池"。与 PAT 不同，memory 模式<b>可用</b>（InMemoryOwnedAppService），服务缺席仅发生在
 * jdbc 模式宿主自换了非 Jauth 仓储等装配边角——页面渲染不支持提示，JSON 回 A0504。
 *
 * @author oatelauser
 */
@Controller
public class MyAppsController {

    /** 列表视图名（selfservice 命名空间模板，本模块视图解析器按白名单认领；自动配置读取）。 */
    public static final String VIEW_MY_APPS = "my-apps";

    /** 注册表单视图名。 */
    public static final String VIEW_MY_APP_NEW = "my-app-new";

    private final @Nullable OwnedAppService ownedAppService;

    private final UserRepository userRepository;

    private final EducationalFlag educational;

    private final ResponseRenderer responseRenderer;

    public MyAppsController(
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
     * 我的应用列表页。
     *
     * @param principal 当前登录主体
     * @param model 视图模型
     * @return 视图名
     */
    @GetMapping("/selfservice/my-apps")
    public String page(@Nullable Principal principal, Model model) {
        model.addAttribute("educational", this.educational.enabled());
        model.addAttribute("appsSupported", this.ownedAppService != null);
        JauthUser user = principal == null ? null : this.userRepository.findByUsername(principal.getName());
        // 非池内主体（嵌入宿主自有用户）与服务缺席同态：渲染空列表态，页面不炸
        if (this.ownedAppService != null && user != null) {
            model.addAttribute("apps", this.ownedAppService.list(user.id()));
        }
        return VIEW_MY_APPS;
    }

    /**
     * 注册表单页。
     *
     * @param model 视图模型
     * @return 视图名
     */
    @GetMapping("/selfservice/my-apps/new")
    public String newAppPage(Model model) {
        model.addAttribute("educational", this.educational.enabled());
        model.addAttribute("appsSupported", this.ownedAppService != null);
        return VIEW_MY_APP_NEW;
    }

    /**
     * 注册 JSON：唯一一次回显明文 client_secret（机密应用）。
     *
     * @param request 注册请求（名称 + redirect URIs 多行文本 + 类型）
     * @param principal 当前登录主体
     * @return SPI 渲染的成功体（confidential 时 data.clientSecret 为明文，仅此一次）
     */
    @PostMapping(
            value = "/selfservice/my-apps",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Object register(@RequestBody RegisterRequest request, @Nullable Principal principal) {
        OwnedAppService service = requireService();
        JauthUser user = requireUser(principal);
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty() || name.length() > OwnedAppService.NAME_MAX_LENGTH) {
            throw new JauthException(SelfServiceErrorCode.A0509);
        }
        List<String> redirectUris;
        try {
            redirectUris = OwnedAppService.parseRedirectUris(request.redirectUris());
        } catch (IllegalArgumentException ex) {
            throw new JauthException(SelfServiceErrorCode.A0510);
        }
        if (redirectUris.isEmpty()) {
            throw new JauthException(SelfServiceErrorCode.A0510);
        }
        OwnedAppService.Registration registration = service.register(
                user.id(), name, Set.copyOf(redirectUris), Boolean.TRUE.equals(request.confidential()));
        Map<String, Object> data = new LinkedHashMap<>(8);
        data.put("name", registration.app().name());
        data.put("clientId", registration.app().clientId());
        data.put("confidential", registration.app().confidential());
        data.put("redirectUris", registration.app().redirectUris());
        if (registration.app().confidential()) {
            data.put("clientSecret", registration.plaintextSecret());
        }
        return this.responseRenderer.renderSuccess(data);
    }

    private OwnedAppService requireService() {
        if (this.ownedAppService == null) {
            throw new JauthException(SelfServiceErrorCode.A0504);
        }
        return this.ownedAppService;
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

    /** 注册请求体：redirectUris 为表单多行文本（每行一个精确 URL），解析与校验单源于 {@link OwnedAppService}。 */
    public record RegisterRequest(String name, String redirectUris, Boolean confidential) {}
}
