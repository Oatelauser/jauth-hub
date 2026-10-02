package io.github.oatelauser.jauth.selfservice.web;

import io.github.oatelauser.jauth.core.org.OrgMember;
import io.github.oatelauser.jauth.core.org.OrgRole;
import io.github.oatelauser.jauth.core.org.OrgService;
import io.github.oatelauser.jauth.core.response.JauthErrorCode;
import io.github.oatelauser.jauth.core.response.JauthException;
import io.github.oatelauser.jauth.core.response.ResponseRenderer;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import io.github.oatelauser.jauth.core.web.RequiresSudo;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * org 成员管理页（v1.3 D3，仅 OWNER）：成员列表（用户名/角色/加入时间）+ 添加（输用户名，池内须存在，
 * 邀请/申请流 v2+ 记档）+ 移除/角色翻转。OWNER 门在 OrgService 服务层单点（A0508）；自操作护栏 A0518。
 * 销毁性动作（移除/改角色）挂 {@code @RequiresSudo}——与 D2 应用管理面的分界一致（非销毁不加）。
 *
 * @author oatelauser
 */
@Controller
public class OrgMembersController {

    /** 成员管理页视图名（selfservice 命名空间模板）。 */
    public static final String VIEW_ORG_MEMBERS = "org-members";

    private final @Nullable OrgService orgService;

    private final UserRepository userRepository;

    private final EducationalFlag educational;

    private final ResponseRenderer responseRenderer;

    /**
     * EI_EXPOSE_REP2 定向豁免：OrgService 是容器单例服务门面（Spring 注入通行形态，与控制器族同取舍）。
     */
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(value = "EI_EXPOSE_REP2")
    public OrgMembersController(
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
     * 成员管理页（仅 OWNER）。
     *
     * @param orgId 路径 org id
     * @param principal 当前登录主体
     * @param model 视图模型
     * @return 视图名
     */
    @GetMapping("/selfservice/orgs/{orgId}/members")
    public String page(@PathVariable("orgId") String orgId, @Nullable Principal principal, Model model) {
        model.addAttribute("educational", this.educational.enabled());
        boolean supported = this.orgService != null;
        model.addAttribute("membersSupported", supported);
        if (!supported) {
            return VIEW_ORG_MEMBERS;
        }
        JauthUser user = requireUser(principal);
        model.addAttribute("orgId", orgId);
        model.addAttribute("members", memberRows(orgId, user, this.orgService, this.userRepository));
        return VIEW_ORG_MEMBERS;
    }

    /**
     * 添加成员 JSON：输入用户名（池内须存在，B0502）；已是成员 A0516（服务层）。
     *
     * @param orgId 归属 org id
     * @param request 添加请求（username）
     * @param principal 当前登录主体
     * @return SPI 渲染的成功体（data 为成员行）
     */
    @PostMapping(
            value = "/selfservice/orgs/{orgId}/members",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Object add(
            @PathVariable("orgId") String orgId, @RequestBody AddRequest request, @Nullable Principal principal) {
        OrgService service = requireService();
        JauthUser user = requireUser(principal);
        String username = request.username() == null ? "" : request.username().trim();
        if (username.isEmpty()) {
            throw new JauthException(JauthErrorCode.B0501);
        }
        JauthUser target = this.userRepository.findByUsername(username);
        if (target == null) {
            throw new JauthException(JauthErrorCode.B0502);
        }
        OrgMember member = service.addMember(orgId, user.id(), target.id());
        return this.responseRenderer.renderSuccess(memberRow(member));
    }

    /**
     * 移除成员 JSON（销毁性：@RequiresSudo）。
     *
     * @param orgId 归属 org id
     * @param userId 目标成员用户 id
     * @param principal 当前登录主体
     * @return SPI 渲染的成功体
     */
    @RequiresSudo
    @DeleteMapping(value = "/selfservice/orgs/{orgId}/members/{userId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Object remove(
            @PathVariable("orgId") String orgId, @PathVariable String userId, @Nullable Principal principal) {
        OrgService service = requireService();
        JauthUser user = requireUser(principal);
        service.removeMember(orgId, user.id(), userId);
        Map<String, Object> data = new LinkedHashMap<>(4);
        data.put("removed", userId);
        return this.responseRenderer.renderSuccess(data);
    }

    /**
     * 角色翻转 JSON（销毁性：@RequiresSudo）：OWNER↔MEMBER，自操作 A0518（服务层）。
     *
     * @param orgId 归属 org id
     * @param userId 目标成员用户 id
     * @param principal 当前登录主体
     * @return SPI 渲染的成功体（data.role 为新角色）
     */
    @RequiresSudo
    @PostMapping(value = "/selfservice/orgs/{orgId}/members/{userId}/role", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Object toggleRole(
            @PathVariable("orgId") String orgId, @PathVariable String userId, @Nullable Principal principal) {
        OrgService service = requireService();
        JauthUser user = requireUser(principal);
        OrgRole newRole = service.isOwner(orgId, userId) ? OrgRole.MEMBER : OrgRole.OWNER;
        OrgMember member = service.changeMemberRole(orgId, user.id(), userId, newRole);
        Map<String, Object> data = memberRow(member);
        return this.responseRenderer.renderSuccess(data);
    }

    /** 行装配包内共径（v1.5 B1b：SSR 页与 JSON 状态面同源，提为 static——不复制行装配逻辑）。 */
    static List<Map<String, Object>> memberRows(
            String orgId, JauthUser actingUser, OrgService orgService, UserRepository userRepository) {
        return orgService.listMembers(orgId, actingUser.id()).stream()
                .map(member -> {
                    Map<String, Object> row = new LinkedHashMap<>(8);
                    JauthUser memberUser = userRepository.findById(member.userId());
                    row.put("userId", member.userId());
                    row.put("username", memberUser == null ? member.userId() : memberUser.username());
                    row.put("role", member.role().name());
                    row.put("createdAt", member.createdAt());
                    return row;
                })
                .toList();
    }

    private static Map<String, Object> memberRow(OrgMember member) {
        Map<String, Object> data = new LinkedHashMap<>(8);
        data.put("userId", member.userId());
        data.put("role", member.role().name());
        return data;
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

    /** 添加成员请求体。 */
    public record AddRequest(String username) {}
}
