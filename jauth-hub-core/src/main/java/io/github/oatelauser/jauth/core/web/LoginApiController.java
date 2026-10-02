package io.github.oatelauser.jauth.core.web;

import io.github.oatelauser.jauth.core.response.JauthException;
import io.github.oatelauser.jauth.core.response.ResponseRenderer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录页 JSON 面（v1.4 B2，四信任页中唯一带认证 POST 的一页）：{@code GET /api/login} 页面状态 +
 * {@code POST /api/login} JSON 认证桥——表单 POST /login 仍由框架 formLogin 过滤器独占消费，本控制器不动它。
 *
 * <p><b>GET 状态</b>：字段与 SSR 页同源（{@code educational}/{@code passkeyEnabled} 同
 * {@link LoginController} 的模型属性；{@code error} = 请求带 {@code ?error} 参数即为 true，与模板
 * {@code ${param.error}} 同语义）+ B1 的 {@link CsrfPayload} CSRF 字段对。认证提交前的页面，链上
 * permitAll（镜像 /login）。
 *
 * <p><b>POST 桥</b>：编舞下沉 {@link JsonLoginService}（锁门/认证/会话动作/落点）；失败两码
 * （A0520 凭据错同形、A0521 锁定期）按登录语义回 <b>401</b> 而非 advice 默认的 200——机器调用方
 * （headless 皮）需要正确的状态位，响应体仍走 {@link ResponseRenderer} 统一形状。成功 200
 * {@code data:{redirectUrl}}——字段名与登录页 passkey 脚本消费的 {@code body.redirectUrl} 同名同义。
 *
 * @author oatelauser
 */
@RestController
public class LoginApiController {

    private final EducationalFlag educational;

    private final PasskeyFlag passkey;

    private final JsonLoginService loginService;

    private final ResponseRenderer responseRenderer;

    public LoginApiController(
            EducationalFlag educational,
            PasskeyFlag passkey,
            JsonLoginService loginService,
            ResponseRenderer responseRenderer) {
        this.educational = educational;
        this.passkey = passkey;
        this.loginService = loginService;
        this.responseRenderer = responseRenderer;
    }

    /**
     * 登录页状态。
     *
     * @param error 框架登录失败重定向携带的 {@code ?error} 参数（缺席为 false）
     * @param request 请求（取 CSRF 惰性请求属性）
     * @return 统一包装的页面状态
     */
    @GetMapping(value = "/api/login", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object loginState(
            @RequestParam(value = "error", required = false) @Nullable String error, HttpServletRequest request) {
        CsrfPayload csrf = CsrfPayload.from(request);
        return this.responseRenderer.renderSuccess(new LoginState(
                this.educational.enabled(),
                this.passkey.enabled(),
                error != null,
                csrf.csrfToken(),
                csrf.csrfHeaderName()));
    }

    /**
     * JSON 登录提交。
     *
     * @param body JSON 载荷（username/password）
     * @param request 当前请求
     * @param response 当前响应
     * @return 成功 200 {@code data:{redirectUrl}}；失败 401 + 统一失败体（A0520/A0521）
     */
    @PostMapping(
            value = "/api/login",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Object login(@RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        try {
            String redirectUrl = this.loginService.login(body.username(), body.password(), request, response);
            return this.responseRenderer.renderSuccess(new LoginResult(redirectUrl));
        } catch (JauthException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(this.responseRenderer.renderFail(ex.getCode(), ex.getMessage()));
        }
    }

    /** 登录页状态载荷（字段名即 B3 前端契约）。 */
    public record LoginState(
            boolean educational,
            boolean passkeyEnabled,
            boolean error,
            @Nullable String csrfToken,
            @Nullable String csrfHeaderName) {}

    /** 登录提交载荷。 */
    public record LoginRequest(String username, String password) {}

    /** 登录成功载荷：落点 URL（saved request 优先，无则 /）。 */
    public record LoginResult(String redirectUrl) {}
}
