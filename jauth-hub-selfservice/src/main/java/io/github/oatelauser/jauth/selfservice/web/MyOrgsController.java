package io.github.oatelauser.jauth.selfservice.web;

import io.github.oatelauser.jauth.core.org.Org;
import io.github.oatelauser.jauth.core.org.OrgService;
import io.github.oatelauser.jauth.core.response.JauthErrorCode;
import io.github.oatelauser.jauth.core.response.JauthException;
import io.github.oatelauser.jauth.core.response.ResponseRenderer;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 我的组织面（B11：org 自助创建，2026-09-30 拍板——任何登录用户可建，创建者自动 OWNER）：JSON 端点；
 * 页面路由 v1.5 B5b 起 302 到 {@code /front/selfservice/my-orgs} 的 SPA 皮（归属列表 + 新建表单，
 * OWNER 行的子页入口由 SPA 持有），查询串原样转发。重名冲突 A0506 由服务层抛、SPA 按错误码提示。
 *
 * <p><b>门控与安全边界</b>：与 MyAppsController 同——路径不在 jauth 协议链认领清单内，认证授权归部署方
 * default 链；控制器守"主体在池"。OrgService 由 starter 无条件供给（两存储模式同在），缺席仅发生在宿主未引
 * starter 的装配边角——JSON 回 A0504（SPA 按状态面 orgsSupported=false 渲染提示态）。
 *
 * @author oatelauser
 */
@Controller
public class MyOrgsController {

    /** 组织名上限（展示面截齐；jauth_org.name 列宽 varchar(50)，先于列宽拒绝超填）。 */
    static final int ORG_NAME_MAX_LENGTH = 50;

    private final @Nullable OrgService orgService;

    private final UserRepository userRepository;

    private final ResponseRenderer responseRenderer;

    /**
     * EI_EXPOSE_REP2 定向豁免：服务/仓储是容器单例门面（Spring 注入通行形态，构造后无可变面暴露）。
     */
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(value = "EI_EXPOSE_REP2")
    public MyOrgsController(
            @Nullable OrgService orgService, UserRepository userRepository, ResponseRenderer responseRenderer) {
        this.orgService = orgService;
        this.userRepository = userRepository;
        this.responseRenderer = responseRenderer;
    }

    /**
     * 我的组织列表页入口：302 到 SPA 皮。
     *
     * @param request 当前请求（查询串原样转发给 SPA）
     * @return 重定向指令
     */
    @GetMapping("/selfservice/my-orgs")
    public String page(HttpServletRequest request) {
        return "redirect:" + SudoController.frontTarget("/front/selfservice/my-orgs", request);
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
