package io.github.oatelauser.jauth.core.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台 API {@code GET /me}（SPEC §4 端点三分：平台 API = 裸用户 JSON，对齐 userinfo 与 GitHub /user）。
 *
 * <p><b>认证</b>：Bearer opaque token，本进程内省（{@link PlatformTokenResolver}，授权令牌 + PAT 叠加，
 * 不走 HTTP 环回）。无 token / 无效 token 拒 401 RFC 6750 形态（WWW-Authenticate: Bearer）——缺失与
 * 失效同形（无效令牌带 error="invalid_token"，防枚举口径与资源服务器一致）。
 *
 * <p><b>响应</b>：裸 JSON {@code sub/username/scope}（scope 空格拼接，RFC 7662 口径），<b>禁
 * ResponseRenderer 包装</b>——端点三分决议；无 code/message 外壳。{@code RestController} 直写响应体
 * （不经 advice），保证包装层无法染指。
 *
 * @author oatelauser
 */
@RestController
public class MeController {

    /** Bearer 方案前缀（RFC 6750 §2.1，大小写不敏感）。 */
    private static final String BEARER_PREFIX = "Bearer ";

    private final PlatformTokenResolver tokenResolver;

    public MeController(PlatformTokenResolver tokenResolver) {
        this.tokenResolver = tokenResolver;
    }

    /**
     * 当前令牌身份。
     *
     * @param request 请求（取 Authorization 头）
     * @param response 响应（401 时直写挑战头）
     * @return 200 时的身份视图（裸 JSON）
     * @throws IOException 直写响应失败
     */
    @GetMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public MeView me(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String tokenValue = bearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (!StringUtils.hasText(tokenValue)) {
            challenge(response, "invalid_request");
            return null;
        }
        PlatformTokenResolver.MeIdentity identity = tokenResolver.resolve(tokenValue);
        if (identity == null) {
            challenge(response, "invalid_token");
            return null;
        }
        return new MeView(identity.sub(), identity.username(), String.join(" ", new TreeSet<>(identity.scopes())));
    }

    private static @Nullable String bearerToken(@Nullable String authorizationHeader) {
        if (authorizationHeader == null
                || authorizationHeader.length() <= BEARER_PREFIX.length()
                || !authorizationHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        return authorizationHeader.substring(BEARER_PREFIX.length()).trim();
    }

    /** 401 挑战：RFC 6750 形态 WWW-Authenticate 头，空响应体（RFC 未定义错误载荷，错误信息在头内）。 */
    private static void challenge(HttpServletResponse response, String error) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"" + error + "\"");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * /me 裸 JSON 视图（scope 排序保证输出确定性）。
     *
     * @param sub 稳定用户标识
     * @param username 登录名
     * @param scope 空格拼接的授权 scope
     */
    public record MeView(String sub, String username, String scope) {}
}
