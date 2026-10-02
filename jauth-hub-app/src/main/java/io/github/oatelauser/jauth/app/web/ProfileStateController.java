package io.github.oatelauser.jauth.app.web;

import io.github.oatelauser.jauth.core.response.ResponseRenderer;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.web.CsrfPayload;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 档案页 JSON 状态面（v1.5 B1a，路径族规约：页面路径加 {@code /api} 前缀）：{@code GET /api/profile}
 * 与 SSR 页（{@link ProfileController#page}）同口径——非池内主体/未认证时 username 空串、displayName
 * null（页面不炸的取舍照抄）；POST /api/profile（改显示名）不动。安全边界同 SSR 页：/api/** 归壳层
 * default 链（未认证 401），starter 链不动。组件扫描注册（app 模块 @Controller 同形态）。
 *
 * <p>B3 前端消费契约：{@code data:{ educational, username, displayName, csrfToken, csrfHeaderName }}；
 * displayName 可空（UI 回退 username）。
 *
 * @author oatelauser
 */
@RestController
public class ProfileStateController {

    private final UserRepository userRepository;

    private final EducationalFlag educational;

    private final ResponseRenderer responseRenderer;

    public ProfileStateController(
            UserRepository userRepository, EducationalFlag educational, ResponseRenderer responseRenderer) {
        this.userRepository = userRepository;
        this.educational = educational;
        this.responseRenderer = responseRenderer;
    }

    /**
     * 档案页状态。
     *
     * @param principal 当前登录主体（非池内主体即空档案态，同 SSR 页）
     * @param request 请求（取 CSRF 惰性请求属性）
     * @return 统一包装的页面状态
     */
    @GetMapping(value = "/api/profile", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object state(@Nullable Principal principal, HttpServletRequest request) {
        JauthUser user = principal == null ? null : this.userRepository.findByUsername(principal.getName());
        CsrfPayload csrf = CsrfPayload.from(request);
        return this.responseRenderer.renderSuccess(new ProfileState(
                this.educational.enabled(),
                user == null ? "" : user.username(),
                user == null ? null : user.displayName(),
                csrf.csrfToken(),
                csrf.csrfHeaderName()));
    }

    /** 档案页状态载荷（字段名即 B3 前端契约）。 */
    public record ProfileState(
            boolean educational,
            String username,
            @Nullable String displayName,
            @Nullable String csrfToken,
            @Nullable String csrfHeaderName) {}
}
