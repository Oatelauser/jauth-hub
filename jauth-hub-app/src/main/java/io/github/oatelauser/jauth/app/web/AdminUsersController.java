package io.github.oatelauser.jauth.app.web;

import io.github.oatelauser.jauth.app.user.AccountSecurityService;
import io.github.oatelauser.jauth.core.audit.AuditEvent;
import io.github.oatelauser.jauth.core.audit.AuditEventPublisher;
import io.github.oatelauser.jauth.core.audit.AuditEventType;
import io.github.oatelauser.jauth.core.response.JauthErrorCode;
import io.github.oatelauser.jauth.core.response.JauthException;
import io.github.oatelauser.jauth.core.response.ResponseRenderer;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.util.UuidV7;
import io.github.oatelauser.jauth.core.web.RequiresSudo;
import io.github.oatelauser.springplus.security.annotation.Principal;
import io.github.oatelauser.springplus.security.annotation.RequiresRole;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 用户管理面（B12，SPEC §2/§7：app 专属页面，SUPERADMIN 专属）：页面路由 /admin/users（v1.5 B5b 起
 * 302 到 /front/admin/users 的 SPA 皮）+ 建号/改角色/停用启用/重置密码 JSON 端点。重置密码由管理员在
 * 请求中给定新值，响应不回显明文（与 my-apps 的 secret 不同源：那里是服务端生成须一次性展示，这里明文
 * 只存在于管理员输入处）。
 *
 * <p><b>门控</b>：全端点 {@code @RequiresRole(ROLE_SUPER_ADMIN)}（08 票 GrantedAuthority 路线，403 由
 * spring-plus 渲染）。<b>自操作护栏</b>：改角色/停用启用不可作用于自己（A0513）——操作者自身的 SUPERADMIN +
 * ACTIVE 因此恒不可被本面移除，超管锁死在结构上不可能，无需"最后一个超管"计数检查。
 *
 * @author oatelauser
 */
@Controller
public class AdminUsersController {

    /** jauth_user.username 列宽 varchar(50)，先于列宽拒绝超填。 */
    static final int USERNAME_MAX_LENGTH = 50;

    /** jauth_user.display_name 列宽 varchar(100)。 */
    static final int DISPLAY_NAME_MAX_LENGTH = 100;

    /** SPEC §6：密码 ≥8 位，无复杂度表演。 */
    static final int PASSWORD_MIN_LENGTH = 8;

    /**
     * bcrypt 摘要只取前 72 字节，超长部分静默截断；Spring Security 6.3+ 的 BCryptPasswordEncoder 对超长
     * 直接抛 IllegalArgumentException——先于编码拒绝，避免 500 与"改了密码却截断登录"的静默陷阱。
     */
    static final int PASSWORD_MAX_LENGTH = 72;

    /** 用户列表默认页大小（v1.3 D5 老账⑦；注解 defaultValue 需编译期常量，取 String 形态）。 */
    static final String USERS_PAGE_DEFAULT_SIZE = "20";

    /** 用户列表页大小上限（防 ?size=100000 全表直出）。 */
    static final int USERS_PAGE_MAX_SIZE = 100;

    private final UserRepository userRepository;

    private final PasswordEncoder passwordEncoder;

    private final ResponseRenderer responseRenderer;

    private final AccountSecurityService accountSecurityService;

    private final AuditEventPublisher auditPublisher;

    public AdminUsersController(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            ResponseRenderer responseRenderer,
            AccountSecurityService accountSecurityService,
            AuditEventPublisher auditPublisher) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.responseRenderer = responseRenderer;
        this.accountSecurityService = accountSecurityService;
        this.auditPublisher = auditPublisher;
    }

    /**
     * 用户管理页入口：302 到 SPA 皮（page/size 查询串原样转发，分页状态由 SPA 经状态面取）。
     *
     * @param request 当前请求（查询串原样转发给 SPA）
     * @return 重定向指令
     */
    @RequiresRole(role = RequiresRole.ROLE_SUPER_ADMIN)
    @GetMapping("/admin/users")
    public String page(HttpServletRequest request) {
        return "redirect:" + RootController.frontTarget("/front/admin/users", request);
    }

    /**
     * 分页参数规整（v1.3 D5 老账⑦，家族 PageResponse 契约口径 item/total/pageNum/pageSize/totalPage）：
     * size clamp 到 [1, {@value #USERS_PAGE_MAX_SIZE}]、page clamp 到 [1,totalPage]（空表钉 1）。
     * v1.5 B1a 提为包内共径——SSR 页与 JSON 状态面同源。
     */
    static PageWindow pageWindow(int page, int size, long total) {
        int pageSize = Math.min(Math.max(size, 1), USERS_PAGE_MAX_SIZE);
        int totalPage = (int) ((total + pageSize - 1) / pageSize);
        int pageNum = Math.min(Math.max(page, 1), Math.max(totalPage, 1));
        return new PageWindow(pageNum, pageSize, totalPage);
    }

    /** 规整后的分页窗口（当页行取数区间由 pageNum/pageSize 推导）。 */
    record PageWindow(int pageNum, int pageSize, int totalPage) {}

    /**
     * 建号 JSON：用户名唯一（A0512，并发撞唯一约束同译）；密码 ≥8 位；显示名可选。
     *
     * @param request 建号请求
     * @return SPI 渲染的成功体（data 含新用户摘要，不含任何凭据材料）
     */
    @RequiresRole(role = RequiresRole.ROLE_SUPER_ADMIN)
    @PostMapping(
            value = "/api/admin/users",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Object create(@RequestBody CreateRequest request, @Principal UserDetails admin) {
        String username = requireUsername(request.username());
        String password = requireValidNewPassword(request.password());
        String displayName = normalizeDisplayName(request.displayName());
        if (this.userRepository.findByUsername(username) != null) {
            throw new JauthException(AppErrorCode.A0512);
        }
        JauthUser user = new JauthUser(
                UuidV7.generate().toString(),
                username,
                this.passwordEncoder.encode(password),
                displayName,
                null,
                JauthUser.ROLE_USER,
                JauthUser.STATUS_ACTIVE,
                null,
                Instant.now());
        try {
            this.userRepository.save(user);
        } catch (DuplicateKeyException ex) {
            // check-then-insert 的并发窗口由唯一约束兜底（B8 OrgService 同款翻译）
            throw new JauthException(AppErrorCode.A0512);
        }
        this.auditPublisher.publish(AuditEvent.of(
                AuditEventType.USER_CREATED,
                requireActingAdmin(admin).id(),
                "user",
                user.id(),
                "username=" + username));
        return this.responseRenderer.renderSuccess(summary(user));
    }

    /**
     * 改角色 JSON：SUPERADMIN↔USER 翻转，不可作用于自己（A0513）。
     * 敏感操作（v1.2 C3）：{@code @RequiresSudo}——sudo 开启时强认证过期即 A0515，页面跳 /selfservice/sudo。
     *
     * @param id 目标用户 id
     * @param admin 操作者（spring-plus 注入的登录主体）
     * @return SPI 渲染的成功体（data.role 为翻转后的新角色）
     */
    @RequiresSudo
    @RequiresRole(role = RequiresRole.ROLE_SUPER_ADMIN)
    @PostMapping(
            value = "/api/admin/users/{id}/role",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Object toggleRole(@PathVariable String id, @Principal UserDetails admin) {
        JauthUser target = requireTarget(id, admin);
        String newRole =
                JauthUser.ROLE_SUPERADMIN.equals(target.role()) ? JauthUser.ROLE_USER : JauthUser.ROLE_SUPERADMIN;
        this.userRepository.updateRole(target.id(), newRole);
        this.auditPublisher.publish(AuditEvent.of(
                AuditEventType.USER_ROLE_CHANGED,
                requireActingAdmin(admin).id(),
                "user",
                target.id(),
                "role=" + newRole));
        Map<String, Object> data = new LinkedHashMap<>(4);
        data.put("id", target.id());
        data.put("username", target.username());
        data.put("role", newRole);
        return this.responseRenderer.renderSuccess(data);
    }

    /**
     * 停用/启用 JSON：ACTIVE↔DISABLED 翻换，不可作用于自己（A0513）；停用即时挡登录
     * （AppUserDetailsService 的 status→enabled 接线）并触发全量清剿——授权/会话/PAT 全失效
     * （v1.3 D1：账号死则凭据全死；启用方向不清剿）。
     * 敏感操作（v1.3 D1 用户拍板）：{@code @RequiresSudo}——sudo 开启时强认证过期即 A0515。
     *
     * @param id 目标用户 id
     * @param admin 操作者
     * @return SPI 渲染的成功体（data.status 为翻转后的新状态）
     */
    @RequiresSudo
    @RequiresRole(role = RequiresRole.ROLE_SUPER_ADMIN)
    @PostMapping(
            value = "/api/admin/users/{id}/status",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Object toggleStatus(@PathVariable String id, @Principal UserDetails admin) {
        JauthUser target = requireTarget(id, admin);
        String newStatus =
                JauthUser.STATUS_ACTIVE.equals(target.status()) ? JauthUser.STATUS_DISABLED : JauthUser.STATUS_ACTIVE;
        this.userRepository.updateStatus(target.id(), newStatus);
        this.auditPublisher.publish(AuditEvent.of(
                AuditEventType.USER_STATUS_CHANGED,
                requireActingAdmin(admin).id(),
                "user",
                target.id(),
                "status=" + newStatus));
        if (JauthUser.STATUS_DISABLED.equals(newStatus)) {
            this.accountSecurityService.onUserDisabled(
                    target.username(), target.id(), requireActingAdmin(admin).id());
        }
        Map<String, Object> data = new LinkedHashMap<>(4);
        data.put("id", target.id());
        data.put("username", target.username());
        data.put("status", newStatus);
        return this.responseRenderer.renderSuccess(data);
    }

    /**
     * 重置密码 JSON：新密码由管理员在请求中给定（≥8 位），更新后响应只回摘要不回显明文；目标用户的
     * 授权/令牌与全部会话随重置全量清剿（v1.3 D1，fail-secure：口令已按失窃处理），PAT 保留。
     * 敏感操作（v1.2 C3）：{@code @RequiresSudo}——sudo 开启时强认证过期即 A0515，页面跳 /selfservice/sudo。
     *
     * @param id 目标用户 id
     * @param request 重置请求
     * @param admin 操作者（清剿审计的行为主体）
     * @return SPI 渲染的成功体（data 仅含 id/username）
     */
    @RequiresSudo
    @RequiresRole(role = RequiresRole.ROLE_SUPER_ADMIN)
    @PostMapping(
            value = "/api/admin/users/{id}/password",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Object resetPassword(
            @PathVariable String id, @RequestBody PasswordRequest request, @Principal UserDetails admin) {
        JauthUser target = this.userRepository.findById(id);
        if (target == null) {
            throw new JauthException(JauthErrorCode.B0502);
        }
        String password = requireValidNewPassword(request.password());
        this.userRepository.updatePasswordHash(target.id(), this.passwordEncoder.encode(password));
        this.accountSecurityService.onCredentialsChanged(
                target.username(), target.id(), requireActingAdmin(admin).id(), null);
        Map<String, Object> data = new LinkedHashMap<>(4);
        data.put("id", target.id());
        data.put("username", target.username());
        return this.responseRenderer.renderSuccess(data);
    }

    /** 目标用户查找 + 自操作护栏（角色/状态两操作的共径）。 */
    private JauthUser requireTarget(String id, UserDetails admin) {
        JauthUser target = this.userRepository.findById(id);
        if (target == null) {
            throw new JauthException(JauthErrorCode.B0502);
        }
        if (target.id().equals(requireActingAdmin(admin).id())) {
            throw new JauthException(AppErrorCode.A0513);
        }
        return target;
    }

    /** 操作者解析（SUPER_ADMIN 角色只能来自 jauth_user 池，AppUserDetailsService 映射；落空属装配边角）。 */
    private JauthUser requireActingAdmin(UserDetails admin) {
        JauthUser actingAdmin = this.userRepository.findByUsername(admin.getUsername());
        if (actingAdmin == null) {
            throw new JauthException(JauthErrorCode.B0502);
        }
        return actingAdmin;
    }

    /** 用户名校验：trim 后非空（A0501）、不超列宽（A0502），返回规整值。 */
    private static String requireUsername(String username) {
        String trimmed = username == null ? "" : username.trim();
        if (trimmed.isEmpty()) {
            throw new JauthException(JauthErrorCode.A0501);
        }
        if (trimmed.length() > USERNAME_MAX_LENGTH) {
            throw new JauthException(JauthErrorCode.A0502);
        }
        return trimmed;
    }

    /** 新密码校验：非空（A0501）、长度区间 [8,72]（A0502），返回原样值（密码不做 trim）。 */
    static String requireValidNewPassword(String password) {
        if (!StringUtils.hasText(password)) {
            throw new JauthException(JauthErrorCode.A0501);
        }
        if (password.length() < PASSWORD_MIN_LENGTH || password.length() > PASSWORD_MAX_LENGTH) {
            throw new JauthException(JauthErrorCode.A0502);
        }
        return password;
    }

    /** 显示名规整：可缺省，trim 后为空落 null（UI 回退 username），不超列宽拒绝。 */
    static @Nullable String normalizeDisplayName(String displayName) {
        String trimmed = displayName == null ? null : displayName.trim();
        if (trimmed != null && trimmed.isEmpty()) {
            trimmed = null;
        }
        if (trimmed != null && trimmed.length() > DISPLAY_NAME_MAX_LENGTH) {
            throw new JauthException(JauthErrorCode.A0502);
        }
        return trimmed;
    }

    /** 用户摘要（建号响应与列表行同形，无凭据材料）。 */
    static Map<String, Object> summary(JauthUser user) {
        Map<String, Object> data = new LinkedHashMap<>(8);
        data.put("id", user.id());
        data.put("username", user.username());
        data.put("displayName", user.displayName());
        data.put("role", user.role());
        data.put("status", user.status());
        // v1.5 B3 滑账回收（B5b）：SPA 用户表的 createdAt 列——状态行与建号响应同形带出
        data.put("createdAt", user.createdAt().toString());
        return data;
    }

    /** 建号请求体：displayName 可选，其余必填。 */
    public record CreateRequest(String username, String password, @Nullable String displayName) {}

    /** 重置密码请求体：新密码由管理员给定。 */
    public record PasswordRequest(String password) {}
}
