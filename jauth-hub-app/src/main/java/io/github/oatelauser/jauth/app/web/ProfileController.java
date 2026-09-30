package io.github.oatelauser.jauth.app.web;

import io.github.oatelauser.jauth.core.ratelimit.RateLimiter;
import io.github.oatelauser.jauth.core.response.JauthException;
import io.github.oatelauser.jauth.core.response.ResponseRenderer;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import io.github.oatelauser.jauth.selfservice.web.SelfServiceErrorCode;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 自助档案 + 改密面（B12，任何登录用户）：页面 /profile，JSON 面 /api/profile（改显示名）与
 * /api/profile/password（改密）。
 *
 * <p><b>旧密码核验联动防爆破</b>：失败记 {@link RateLimiter#onLoginFailure(String)}（与登录失败同一计数，
 * 同一阈值锁定）——防已认证会话内爆破旧密码；改密前先查 {@link RateLimiter#isLoginLocked(String)}，锁定期
 * 内不再触达哈希比对；成功清计数（onLoginSuccess）。
 *
 * <p><b>不踢会话</b>（记档取舍）：v1.1 无会话/令牌清剿面，改密后既有会话与已发令牌仍在效——v1.2 候选随
 * back-channel logout 一起做。
 *
 * @author oatelauser
 */
@Controller
public class ProfileController {

    /** 档案视图名（壳层默认 Thymeleaf 解析器）。 */
    public static final String VIEW_PROFILE = "profile";

    private final UserRepository userRepository;

    private final PasswordEncoder passwordEncoder;

    private final RateLimiter rateLimiter;

    private final EducationalFlag educational;

    private final ResponseRenderer responseRenderer;

    public ProfileController(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            RateLimiter rateLimiter,
            EducationalFlag educational,
            ResponseRenderer responseRenderer) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.educational = educational;
        this.responseRenderer = responseRenderer;
    }

    /**
     * 档案页：当前用户显示名 + 改密表单。
     *
     * @param principal 当前登录主体
     * @param model 视图模型
     * @return 视图名
     */
    @GetMapping("/profile")
    public String page(@Nullable Principal principal, Model model) {
        model.addAttribute("educational", this.educational.enabled());
        JauthUser user = principal == null ? null : this.userRepository.findByUsername(principal.getName());
        model.addAttribute("username", user == null ? "" : user.username());
        model.addAttribute("displayName", user == null ? null : user.displayName());
        return VIEW_PROFILE;
    }

    /**
     * 改显示名 JSON：空串等价清空（UI 回退 username）。
     *
     * @param request 改名请求
     * @param principal 当前登录主体
     * @return SPI 渲染的成功体（data.displayName 为规整后的新值）
     */
    @PostMapping(
            value = "/api/profile",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Object updateDisplayName(@RequestBody UpdateDisplayNameRequest request, @Nullable Principal principal) {
        JauthUser user = requireUser(principal);
        String displayName = AdminUsersController.normalizeDisplayName(request.displayName());
        this.userRepository.updateDisplayName(user.id(), displayName);
        Map<String, Object> data = new LinkedHashMap<>(4);
        data.put("username", user.username());
        data.put("displayName", displayName);
        return this.responseRenderer.renderSuccess(data);
    }

    /**
     * 改密 JSON：旧密码核验（错/锁定 → A0514 并联动登录失败计数），新密码 ≥8 位；成功不回显任何凭据。
     *
     * @param request 改密请求
     * @param principal 当前登录主体
     * @return SPI 渲染的成功体（data 仅含 username）
     */
    @PostMapping(
            value = "/api/profile/password",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Object changePassword(@RequestBody PasswordChangeRequest request, @Nullable Principal principal) {
        JauthUser user = requireUser(principal);
        // 先判锁再验旧密码：锁定期内不再给爆破面（与 LoginLockoutFilter 的登录路径同构）
        if (this.rateLimiter.isLoginLocked(user.username())) {
            throw new JauthException(AppErrorCode.A0514);
        }
        if (!oldPasswordMatches(user, request.oldPassword())) {
            this.rateLimiter.onLoginFailure(user.username());
            throw new JauthException(AppErrorCode.A0514);
        }
        String newPassword = AdminUsersController.requireValidNewPassword(request.newPassword());
        this.userRepository.updatePasswordHash(user.id(), this.passwordEncoder.encode(newPassword));
        this.rateLimiter.onLoginSuccess(user.username());
        Map<String, Object> data = new LinkedHashMap<>(4);
        data.put("username", user.username());
        return this.responseRenderer.renderSuccess(data);
    }

    /** 旧密码比对：null/空串按失败计（DelegatingPasswordEncoder 对 null 会抛，先挡）。 */
    private boolean oldPasswordMatches(JauthUser user, @Nullable String oldPassword) {
        if (!StringUtils.hasText(oldPassword)) {
            return false;
        }
        return this.passwordEncoder.matches(oldPassword, user.passwordHash());
    }

    /** 主体在池守卫（与自助页同语义：非池主体多为嵌入宿主自有用户/令牌主体）。 */
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

    /** 改显示名请求体。 */
    public record UpdateDisplayNameRequest(@Nullable String displayName) {}

    /** 改密请求体。 */
    public record PasswordChangeRequest(@Nullable String oldPassword, @Nullable String newPassword) {}
}
