package io.github.oatelauser.jauth.selfservice.web;

import io.github.oatelauser.jauth.core.org.Org;
import io.github.oatelauser.jauth.core.org.OrgRepository;
import io.github.oatelauser.jauth.core.org.OrgService;
import io.github.oatelauser.jauth.core.response.JauthErrorCode;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * org 应用页（B11，2026-09-30 拍板移入本批）：org 应用的注册/列表，注册操作者须为该 org OWNER。
 *
 * <p><b>页面</b>（GET /selfservice/orgs/{orgId}/apps，仅 OWNER）：org 应用列表（OwnedAppService.listOrg）+
 * 注册表单（照 my-app-new 结构：名称/redirect URIs/类型）——机密应用的 client_secret 明文只在注册响应出现
 * 一次，直接渲染进"仅此一次"横幅。构造惯例与个人应用同源（OwnedAppService 单核），owner=ofOrg；org 应用
 * 的 scope 封顶走安装审批 ceiling，注册面不做 ceiling。
 *
 * <p><b>门控与安全边界</b>：OWNER 门在控制器入口（OrgService.isOwner → A0508）——OwnedAppService 是
 * selfservice 门面非 core 域服务，注册面无服务层门可依托；认证授权归部署方 default 链，控制器守"主体在池"。
 *
 * @author oatelauser
 */
@Controller
public class OrgAppsController {

    /** org 应用页视图名（selfservice 命名空间模板，本模块视图解析器按白名单认领；自动配置读取）。 */
    public static final String VIEW_ORG_APPS = "org-apps";

    private final @Nullable OwnedAppService ownedAppService;

    private final @Nullable OrgService orgService;

    private final @Nullable OrgRepository orgRepository;

    private final UserRepository userRepository;

    private final EducationalFlag educational;

    private final ResponseRenderer responseRenderer;

    public OrgAppsController(
            @Nullable OwnedAppService ownedAppService,
            @Nullable OrgService orgService,
            @Nullable OrgRepository orgRepository,
            UserRepository userRepository,
            EducationalFlag educational,
            ResponseRenderer responseRenderer) {
        this.ownedAppService = ownedAppService;
        this.orgService = orgService;
        this.orgRepository = orgRepository;
        this.userRepository = userRepository;
        this.educational = educational;
        this.responseRenderer = responseRenderer;
    }

    /**
     * org 应用列表 + 注册表单页（仅 OWNER）。
     *
     * @param orgId 路径 org id
     * @param principal 当前登录主体
     * @param model 视图模型
     * @return 视图名
     */
    @GetMapping("/selfservice/orgs/{orgId}/apps")
    public String page(@PathVariable("orgId") String orgId, @Nullable Principal principal, Model model) {
        model.addAttribute("educational", this.educational.enabled());
        boolean supported = this.ownedAppService != null && this.orgService != null && this.orgRepository != null;
        model.addAttribute("orgAppsSupported", supported);
        if (!supported) {
            return VIEW_ORG_APPS;
        }
        JauthUser user = requireUser(principal);
        requireOwner(orgId, user);
        Org org = requireOrg(orgId);
        model.addAttribute("org", org);
        model.addAttribute("apps", this.ownedAppService.listOrg(orgId));
        return VIEW_ORG_APPS;
    }

    /**
     * 注册 org 应用 JSON：唯一一次回显明文 client_secret（机密应用）。
     *
     * @param orgId 归属 org id
     * @param request 注册请求（名称 + redirect URIs 多行文本 + 类型）
     * @param principal 当前登录主体
     * @return SPI 渲染的成功体（confidential 时 data.clientSecret 为明文，仅此一次）
     */
    @PostMapping(
            value = "/selfservice/orgs/{orgId}/apps",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Object register(
            @PathVariable("orgId") String orgId, @RequestBody RegisterRequest request, @Nullable Principal principal) {
        if (this.ownedAppService == null) {
            throw new JauthException(SelfServiceErrorCode.A0504);
        }
        JauthUser user = requireUser(principal);
        requireOwner(orgId, user);
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
        OwnedAppService.Registration registration = this.ownedAppService.registerOrg(
                orgId, name, Set.copyOf(redirectUris), Boolean.TRUE.equals(request.confidential()));
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
