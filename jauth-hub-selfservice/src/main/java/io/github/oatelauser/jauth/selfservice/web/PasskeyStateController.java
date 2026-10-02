package io.github.oatelauser.jauth.selfservice.web;

import io.github.oatelauser.jauth.core.passkey.JauthUserEntityRepository;
import io.github.oatelauser.jauth.core.response.JauthException;
import io.github.oatelauser.jauth.core.response.ResponseRenderer;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.web.CsrfPayload;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通行密钥管理页 JSON 状态面（v1.5 B1c，路径族规约：页面路径加 {@code /api} 前缀）：{@code GET
 * /api/selfservice/passkey} 与 SSR 页（{@link PasskeyController#page}）同口径——passkeyEnabled = 凭据
 * 仓储在场性（starter passkey 开关，照 SSR 页门控），credentials 行装配复用
 * {@link PasskeyController#credentialViews}（B1c 提取的包内 statics，不复制）。安全边界同 SSR 页：路径不在
 * jauth 协议链认领清单内，认证归部署方 default 链（starter 链不动）。
 *
 * <p>注册/删除 ceremony 仍走框架端点（POST /webauthn/register/options、POST /webauthn/register、DELETE
 * /webauthn/register/{credentialId}——非 jauth 渲染形状），前端原样复用（jauth-hub-front/src/webauthn.js
 * 先例）；本状态面只补凭据<b>列表</b>与页面态。csrf 对必须在场：ceremony 端点受 CsrfFilter 保护。
 *
 * <p>B3 前端消费契约：{@code data:{ educational, passkeyEnabled, credentials, csrfToken, csrfHeaderName }}；
 * credentials 字段恒在场（passkey 关时空列表，供前端类型稳定分支——SSR 模板属性缺席语义在 JSON 面收敛为
 * 空集）。
 *
 * @author oatelauser
 */
@RestController
public class PasskeyStateController {

    private final ObjectProvider<UserCredentialRepository> credentials;

    private final UserRepository userRepository;

    private final EducationalFlag educational;

    private final ResponseRenderer responseRenderer;

    public PasskeyStateController(
            ObjectProvider<UserCredentialRepository> credentials,
            UserRepository userRepository,
            EducationalFlag educational,
            ResponseRenderer responseRenderer) {
        this.credentials = credentials;
        this.userRepository = userRepository;
        this.educational = educational;
        this.responseRenderer = responseRenderer;
    }

    /**
     * 通行密钥管理页状态。
     *
     * @param principal 当前登录主体（仓储在场时须在池，同 SSR 页；仓储缺席静默降级不触门）
     * @param request 请求（取 CSRF 惰性请求属性）
     * @return 统一包装的页面状态
     */
    @GetMapping(value = "/api/selfservice/passkey", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object state(@Nullable Principal principal, HttpServletRequest request) {
        UserCredentialRepository repository = this.credentials.getIfAvailable();
        List<PasskeyController.PasskeyCredentialView> rows = List.of();
        if (repository != null) {
            JauthUser user = requireUser(principal);
            rows = PasskeyController.credentialViews(
                    repository.findByUserId(JauthUserEntityRepository.userHandle(user.id())));
        }
        CsrfPayload csrf = CsrfPayload.from(request);
        return this.responseRenderer.renderSuccess(new PasskeyState(
                this.educational.enabled(), repository != null, rows, csrf.csrfToken(), csrf.csrfHeaderName()));
    }

    /** 主体在池守卫（与 PasskeyController 同语义：仓储在场才求值）。 */
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

    /** 通行密钥页状态载荷（字段名即 B3 前端契约；credentials 行与 SSR 页模型同形同源）。 */
    public record PasskeyState(
            boolean educational,
            boolean passkeyEnabled,
            List<PasskeyController.PasskeyCredentialView> credentials,
            @Nullable String csrfToken,
            @Nullable String csrfHeaderName) {

        /** credentials 防御性拷贝（SpotBugs EI_EXPOSE_REP：出入均不可变）。 */
        public PasskeyState {
            credentials = credentials == null ? List.of() : List.copyOf(credentials);
        }
    }
}
