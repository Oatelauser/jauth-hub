package io.github.oatelauser.jauth.selfservice.web;

import io.github.oatelauser.jauth.core.passkey.JauthUserEntityRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 通行密钥管理页路由（v1.2 C2；v1.5 B5b SSR 拆除）：GET 无条件 302 到
 * {@code /front/selfservice/passkey} 的 SPA 皮，查询串原样转发。注册/删除走 C1 已挂链的框架端点
 * （POST /webauthn/register(options)、DELETE /webauthn/register/{id}，SPA 的 webauthn.js 带 CSRF）；
 * 凭据列表行装配（{@link #credentialViews}）与行形状由 JSON 状态面（{@link PasskeyStateController}）消费。
 *
 * <p><b>数据源</b>：{@code UserCredentialRepository#findByUserId}，user handle = jauth_user.id 的 UTF-8
 * {@link JauthUserEntityRepository#userHandle(String) Bytes}（编解码单点）。credentialId 以 base64url 出
 * 行——既是展示缩略也是 DELETE 路径段的原始值（URL 安全字符集）。
 *
 * <p><b>安全边界</b>：路径不在 jauth 协议链认领清单内（SPEC §2 宿主链共存规则），认证与授权由部署方
 * default 链负责。
 *
 * @author oatelauser
 */
@Controller
public class PasskeyController {

    /** 列表项 credentialId 的展示缩略长度（完整值仅进删除路径段，避免长串撑坏表格）。 */
    static final int CREDENTIAL_ID_DISPLAY_LENGTH = 12;

    /**
     * 通行密钥管理页入口：302 到 SPA 皮。
     *
     * @param request 当前请求（查询串原样转发给 SPA）
     * @return 重定向指令
     */
    @GetMapping("/selfservice/passkey")
    public String page(HttpServletRequest request) {
        return "redirect:" + SudoController.frontTarget("/front/selfservice/passkey", request);
    }

    /**
     * 凭据行装配（v1.5 B1c 提升包内 static）：JSON 状态面（{@link PasskeyStateController}）共用的单点，
     * 不复制。
     *
     * @param records 框架凭据记录
     * @return 列表行
     */
    static List<PasskeyCredentialView> credentialViews(List<CredentialRecord> records) {
        List<PasskeyCredentialView> views = new ArrayList<>();
        for (CredentialRecord record : records) {
            String credentialId = record.getCredentialId().toBase64UrlString();
            views.add(new PasskeyCredentialView(
                    credentialId,
                    credentialId.substring(0, Math.min(CREDENTIAL_ID_DISPLAY_LENGTH, credentialId.length())) + "…",
                    record.getLabel(),
                    record.getCreated(),
                    record.getLastUsed()));
        }
        return views;
    }

    /** 凭据列表行（label 可空——框架注册面不强制；SPA 回退字典"未命名"）。 */
    public record PasskeyCredentialView(
            String credentialId,
            String credentialIdShort,
            @Nullable String label,
            Instant createdAt,
            @Nullable Instant lastUsedAt) {}
}
