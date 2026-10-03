package io.github.oatelauser.jauth.selfservice.web;

import io.github.oatelauser.jauth.core.audit.AuditEvent;
import io.github.oatelauser.jauth.core.audit.AuditEventPublisher;
import io.github.oatelauser.jauth.core.audit.AuditEventType;
import io.github.oatelauser.jauth.core.client.ClientOwner;
import io.github.oatelauser.jauth.core.ratelimit.RateLimiter;
import io.github.oatelauser.jauth.core.response.JauthException;
import io.github.oatelauser.jauth.core.response.ResponseRenderer;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.web.RequiresSudo;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 我的应用面（B10：个人应用免安装审批的自助注册面，SPEC §3/§7 v1.1 应用管理）：JSON 端点；页面路由
 * v1.5 B5b 起 302 到 {@code /front/selfservice/my-apps} 的 SPA 皮（注册并入 my-apps 页内对话框，B0 普查
 * 决议——/new 旧路同指该页），查询串原样转发。机密应用的 client_secret 明文只在注册响应出现一次。
 * scopes 不进表单：allowed scopes = scope 目录全集，封顶由 consent 页把关（个人应用无 ceiling）。
 *
 * <p><b>门控与安全边界</b>：与 PatController 同——路径不在 jauth 协议链认领清单内，认证授权归部署方 default 链；
 * 控制器守"主体在池"。与 PAT 不同，memory 模式<b>可用</b>（InMemoryOwnedAppService），服务缺席仅发生在
 * jdbc 模式宿主自换了非 Jauth 仓储等装配边角——JSON 回 A0504（SPA 按状态面 appsSupported=false 渲染提示态）。
 *
 * @author oatelauser
 */
@Controller
public class MyAppsController {

    private final @Nullable OwnedAppService ownedAppService;

    private final UserRepository userRepository;

    private final ResponseRenderer responseRenderer;

    private final AuditEventPublisher auditPublisher;

    private final RateLimiter rateLimiter;

    /**
     * EI_EXPOSE_REP2 定向豁免：OwnedAppService 是抽象类（SpotBugs 视可变表示），实为容器单例服务门面
     * （Spring 注入通行形态，构造后无可变面暴露——与 PatController 存接口不豁免的差异仅在类型形状）。
     */
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(value = "EI_EXPOSE_REP2")
    public MyAppsController(
            @Nullable OwnedAppService ownedAppService,
            UserRepository userRepository,
            ResponseRenderer responseRenderer,
            AuditEventPublisher auditPublisher,
            RateLimiter rateLimiter) {
        this.ownedAppService = ownedAppService;
        this.userRepository = userRepository;
        this.responseRenderer = responseRenderer;
        this.auditPublisher = auditPublisher;
        this.rateLimiter = rateLimiter;
    }

    /**
     * 我的应用列表页入口：302 到 SPA 皮（注册对话框在页内）。
     *
     * @param request 当前请求（查询串原样转发给 SPA）
     * @return 重定向指令
     */
    @GetMapping("/selfservice/my-apps")
    public String page(HttpServletRequest request) {
        return "redirect:" + SudoController.frontTarget("/front/selfservice/my-apps", request);
    }

    /**
     * 旧注册表单页入口：302 到 SPA 皮（/new 无独立 SPA 路由，注册是 my-apps 页内对话框）。
     *
     * @param request 当前请求
     * @return 重定向指令
     */
    @GetMapping("/selfservice/my-apps/new")
    public String newAppPage(HttpServletRequest request) {
        return "redirect:" + SudoController.frontTarget("/front/selfservice/my-apps", request);
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
        requireCreationQuota(user.id());
        OwnedAppService.Registration registration = service.register(
                user.id(),
                OwnedAppService.requireName(request.name()),
                OwnedAppService.parseRedirects(request.redirectUris()),
                Boolean.TRUE.equals(request.confidential()));
        Map<String, Object> data = OwnedAppService.appData(registration.app());
        if (registration.app().confidential()) {
            data.put("clientSecret", registration.plaintextSecret());
        }
        this.auditPublisher.publish(AuditEvent.of(
                AuditEventType.CLIENT_REGISTERED,
                user.id(),
                "client",
                registration.app().id(),
                "owner=user name=" + registration.app().name()));
        return this.responseRenderer.renderSuccess(data);
    }

    /** 创建节流（v1.3 D5 老账④）：每主体小时窗（共享限流器配额，键前缀区分创建面）。 */
    private void requireCreationQuota(String userId) {
        if (!this.rateLimiter.consume("create-app:" + userId).allowed()) {
            throw new JauthException(SelfServiceErrorCode.A0519);
        }
    }

    /**
     * 轮转 secret JSON（v1.3 D2）：旧值即刻失效、不焚令牌（拍板）；新明文仅此一次回显。
     * 敏感操作（销毁性换钥）：{@code @RequiresSudo}——C3 家族形态。
     *
     * @param id 应用 id
     * @param principal 当前登录主体
     * @return SPI 渲染的成功体（data.clientSecret 为新明文，仅此一次）
     */
    @RequiresSudo
    @PostMapping(value = "/selfservice/my-apps/{id}/secret", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Object rotateSecret(@PathVariable String id, @Nullable Principal principal) {
        OwnedAppService service = requireService();
        JauthUser user = requireUser(principal);
        OwnedAppService.Registration registration = service.rotateSecret(ClientOwner.ofUser(user.id()), id);
        Map<String, Object> data = OwnedAppService.appData(registration.app());
        data.put("clientSecret", registration.plaintextSecret());
        return this.responseRenderer.renderSuccess(data);
    }

    /**
     * 编辑 JSON（v1.3 D2）：改名与 redirect 白名单（校验口径同注册）；secret 与归属不动。非销毁性操作，
     * 不挂 sudo（与轮转/删除的门径分界记档于此）。
     *
     * @param id 应用 id
     * @param request 编辑请求（名称 + redirect URIs 多行文本）
     * @param principal 当前登录主体
     * @return SPI 渲染的成功体（data 为更新后的应用摘要）
     */
    @PostMapping(
            value = "/selfservice/my-apps/{id}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Object update(@PathVariable String id, @RequestBody UpdateRequest request, @Nullable Principal principal) {
        OwnedAppService service = requireService();
        JauthUser user = requireUser(principal);
        OwnedAppService.OwnedApp app = service.update(
                ClientOwner.ofUser(user.id()),
                id,
                OwnedAppService.requireName(request.name()),
                OwnedAppService.parseRedirects(request.redirectUris()));
        return this.responseRenderer.renderSuccess(OwnedAppService.appData(app));
    }

    /**
     * 删除 JSON（v1.3 D2，级联全焚）：授权/consent/安装随删、族谱按 client 烧断（级联序在服务层）。
     * 敏感操作：{@code @RequiresSudo}。
     *
     * @param id 应用 id
     * @param principal 当前登录主体
     * @return SPI 渲染的成功体（data.id 为已删应用 id）
     */
    @RequiresSudo
    @DeleteMapping(value = "/selfservice/my-apps/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Object delete(@PathVariable String id, @Nullable Principal principal) {
        OwnedAppService service = requireService();
        JauthUser user = requireUser(principal);
        service.delete(ClientOwner.ofUser(user.id()), id);
        this.auditPublisher.publish(AuditEvent.of(
                AuditEventType.CLIENT_DELETED,
                user.id(),
                "client",
                id,
                "owner=user cascade=authorizations,consent,installations,family"));
        Map<String, Object> data = new LinkedHashMap<>(4);
        data.put("id", id);
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

    /** 编辑请求体（v1.3 D2）：口径同注册的 name/redirectUris 两件。 */
    public record UpdateRequest(String name, String redirectUris) {}
}
