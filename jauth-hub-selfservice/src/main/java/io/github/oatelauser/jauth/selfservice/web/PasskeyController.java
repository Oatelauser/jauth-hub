package io.github.oatelauser.jauth.selfservice.web;

import io.github.oatelauser.jauth.core.passkey.JauthUserEntityRepository;
import io.github.oatelauser.jauth.core.response.JauthException;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 通行密钥管理页（v1.2 C2）：注册表单（label 输入 + 浏览器原生 ceremony）+ 凭据列表；页面纯 SSR 渲染，
 * 注册/删除走 C1 已挂链的框架端点（POST /webauthn/register(options)、DELETE /webauthn/register/{id}，
 * JS 带 CSRF——控制器不自建 JSON 面）。
 *
 * <p><b>开关门控</b>（SPEC §5 默认关）：{@link UserCredentialRepository} bean 仅在
 * {@code jauth-hub.passkey.enabled=true} 时由 starter 装配——缺席即渲染"未启用"提示（200，不 500），
 * 与 PAT 页的 memory 门控同构。仓储经 {@link ObjectProvider} 持有（照 {@link AuthorizedAppsController}
 * 对框架服务的既有形态）。
 *
 * <p><b>数据源</b>：{@link UserCredentialRepository#findByUserId}，user handle = jauth_user.id 的 UTF-8
 * {@link JauthUserEntityRepository#userHandle(String) Bytes}（编解码单点）。credentialId 以 base64url 出
 * 模板——既是展示缩略也是 DELETE 路径段的原始值（URL 安全字符集）。
 *
 * <p><b>安全边界</b>：路径不在 jauth 协议链认领清单内（SPEC §2 宿主链共存规则），认证与授权由部署方
 * default 链负责；本控制器只守"主体可用"（principal 非空且在 jauth 用户池）。
 *
 * @author oatelauser
 */
@Controller
public class PasskeyController {

    /** 视图名（selfservice 命名空间模板，本模块视图解析器白名单认领）。 */
    public static final String VIEW_PASSKEY = "passkey";

    /** 列表项 credentialId 的展示缩略长度（完整值仅进删除路径段，避免长串撑坏表格）。 */
    static final int CREDENTIAL_ID_DISPLAY_LENGTH = 12;

    private final ObjectProvider<UserCredentialRepository> credentials;

    private final UserRepository userRepository;

    private final EducationalFlag educational;

    public PasskeyController(
            ObjectProvider<UserCredentialRepository> credentials,
            UserRepository userRepository,
            EducationalFlag educational) {
        this.credentials = credentials;
        this.userRepository = userRepository;
        this.educational = educational;
    }

    /**
     * 通行密钥管理页。
     *
     * @param principal 当前登录主体
     * @param model 视图模型
     * @return 视图名
     */
    @GetMapping("/selfservice/passkey")
    public String page(@Nullable Principal principal, Model model) {
        model.addAttribute("educational", this.educational.enabled());
        UserCredentialRepository repository = this.credentials.getIfAvailable();
        model.addAttribute("passkeyEnabled", repository != null);
        if (repository == null) {
            return VIEW_PASSKEY;
        }
        JauthUser user = requireUser(principal);
        model.addAttribute(
                "credentials",
                credentialViews(repository.findByUserId(JauthUserEntityRepository.userHandle(user.id()))));
        return VIEW_PASSKEY;
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

    /**
     * 凭据行装配（v1.5 B1c 提升包内 static）：SSR 页与 JSON 状态面（{@link PasskeyStateController}）
     * 共用单点，不复制。
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

    /** 凭据列表行（label 可空——框架注册面不强制；模板回退 i18n 未命名）。 */
    public record PasskeyCredentialView(
            String credentialId,
            String credentialIdShort,
            @Nullable String label,
            Instant createdAt,
            @Nullable Instant lastUsedAt) {}
}
