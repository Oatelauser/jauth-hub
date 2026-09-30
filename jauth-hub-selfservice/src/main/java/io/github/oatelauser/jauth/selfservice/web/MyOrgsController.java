package io.github.oatelauser.jauth.selfservice.web;

import io.github.oatelauser.jauth.core.org.Org;
import io.github.oatelauser.jauth.core.org.OrgService;
import io.github.oatelauser.jauth.core.response.JauthErrorCode;
import io.github.oatelauser.jauth.core.response.JauthException;
import io.github.oatelauser.jauth.core.response.ResponseRenderer;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 我的组织页（B11：org 自助创建，2026-09-30 拍板——任何登录用户可建，创建者自动 OWNER）。
 *
 * <p><b>页面</b>：当前用户归属 org 列表（OrgService.findByUser，含角色徽标）+ "新建组织"表单；创建走页内
 * 原生 JS 调 JSON 端点（照 my-apps 惯例），重名冲突 A0506 由服务层抛、页面按错误码提示。每行 OWNER 角色
 * 显示该 org 的安装审批页与 org 应用页入口（MEMBER 不显示——入口控制，越权访问由服务层 A0508 兜底）。
 *
 * <p><b>门控与安全边界</b>：与 MyAppsController 同——路径不在 jauth 协议链认领清单内，认证授权归部署方
 * default 链；控制器守"主体在池"。OrgService 由 starter 无条件供给（两存储模式同在），缺席仅发生在宿主未引
 * starter 的装配边角——页面渲染不支持提示，JSON 回 A0504。
 *
 * @author oatelauser
 */
@Controller
public class MyOrgsController {

    /** 列表视图名（selfservice 命名空间模板，本模块视图解析器按白名单认领；自动配置读取）。 */
    public static final String VIEW_MY_ORGS = "my-orgs";

    /** 组织名上限（展示面截齐；jauth_org.name 列宽 varchar(50)，先于列宽拒绝超填）。 */
    static final int ORG_NAME_MAX_LENGTH = 50;

    private final @Nullable OrgService orgService;

    private final UserRepository userRepository;

    private final EducationalFlag educational;

    private final ResponseRenderer responseRenderer;

    public MyOrgsController(
            @Nullable OrgService orgService,
            UserRepository userRepository,
            EducationalFlag educational,
            ResponseRenderer responseRenderer) {
        this.orgService = orgService;
        this.userRepository = userRepository;
        this.educational = educational;
        this.responseRenderer = responseRenderer;
    }

    /**
     * 我的组织列表页。
     *
     * @param principal 当前登录主体
     * @param model 视图模型
     * @return 视图名
     */
    @GetMapping("/selfservice/my-orgs")
    public String page(@Nullable Principal principal, Model model) {
        model.addAttribute("educational", this.educational.enabled());
        model.addAttribute("orgsSupported", this.orgService != null);
        JauthUser user = principal == null ? null : this.userRepository.findByUsername(principal.getName());
        // 非池内主体（嵌入宿主自有用户）与服务缺席同态：渲染空列表态，页面不炸（MyAppsController 同款取舍）
        if (this.orgService != null && user != null) {
            model.addAttribute("orgs", this.orgService.findByUser(user.id()));
        }
        return VIEW_MY_ORGS;
    }

    /**
     * 创建组织 JSON：OrgService.create（创建者自动 OWNER，审计 org.created 自然落）。
     *
     * @param request 创建请求（组织名）
     * @param principal 当前登录主体
     * @return SPI 渲染的成功体（data.orgId/data.name）
     */
    @PostMapping(
            value = "/selfservice/my-orgs",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Object create(@RequestBody CreateRequest request, @Nullable Principal principal) {
        OrgService service = requireService();
        JauthUser user = requireUser(principal);
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            throw new JauthException(JauthErrorCode.A0501);
        }
        if (name.length() > ORG_NAME_MAX_LENGTH) {
            throw new JauthException(JauthErrorCode.A0502);
        }
        Org org = service.create(name, user.id());
        Map<String, Object> data = new LinkedHashMap<>(4);
        data.put("orgId", org.id());
        data.put("name", org.name());
        return this.responseRenderer.renderSuccess(data);
    }

    private OrgService requireService() {
        if (this.orgService == null) {
            throw new JauthException(SelfServiceErrorCode.A0504);
        }
        return this.orgService;
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

    /** 创建请求体。 */
    public record CreateRequest(String name) {}
}
