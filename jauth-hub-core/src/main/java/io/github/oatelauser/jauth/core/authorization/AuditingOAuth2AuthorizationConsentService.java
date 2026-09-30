package io.github.oatelauser.jauth.core.authorization;

import io.github.oatelauser.jauth.core.audit.AuditEvent;
import io.github.oatelauser.jauth.core.audit.AuditEventPublisher;
import io.github.oatelauser.jauth.core.audit.AuditEventType;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.util.Assert;

/**
 * consent 服务审计装饰（票 07：consent 接受打审计点，save 路径）。
 *
 * <p><b>落点在 consent 服务 save 而非自有控制器</b>（对任务描述的一处修正）：consent 的真实提交
 * 发生在框架 OAuth2AuthorizationRequestAuthenticationProvider（POST 授权端点携带勾选 scope），
 * 自有 ConsentController 只渲染确认页——挂在存储层才能不漏不重地捕获每次接受。两模式
 * （InMemory/Jdbc consent 服务）由装配方统一包本装饰。
 *
 * <p><b>remove（看板"解除授权"清 consent）不在此打点</b>：撤销语义由授权服务装饰的 token.revoked 承载
 * （selfservice 一键 revoke 双 remove 已记授权撤销行，consent 清理是其附带动作）。
 *
 * <p>委托面取 JDK 函数捕获（框架接口不存成员——与 AuditingOAuth2AuthorizationService 同款取舍，
 * SpotBugs EI_EXPOSE_REP2 洁净）。
 *
 * @author oatelauser
 */
public class AuditingOAuth2AuthorizationConsentService implements OAuth2AuthorizationConsentService {

    private final Consumer<OAuth2AuthorizationConsent> saveDelegate;

    private final Consumer<OAuth2AuthorizationConsent> removeDelegate;

    private final BiFunction<String, String, @Nullable OAuth2AuthorizationConsent> findByIdDelegate;

    private final AuditEventPublisher auditPublisher;

    public AuditingOAuth2AuthorizationConsentService(
            OAuth2AuthorizationConsentService delegate, AuditEventPublisher auditPublisher) {
        Assert.notNull(delegate, "delegate cannot be null");
        Assert.notNull(auditPublisher, "auditPublisher cannot be null");
        this.saveDelegate = delegate::save;
        this.removeDelegate = delegate::remove;
        this.findByIdDelegate = delegate::findById;
        this.auditPublisher = auditPublisher;
    }

    @Override
    public void save(OAuth2AuthorizationConsent consent) {
        this.saveDelegate.accept(consent);
        this.auditPublisher.publish(AuditEvent.of(
                AuditEventType.CONSENT_ACCEPTED,
                consent.getPrincipalName(),
                "client",
                consent.getRegisteredClientId(),
                "scopes=" + String.join(" ", consent.getScopes())));
    }

    @Override
    public void remove(OAuth2AuthorizationConsent consent) {
        this.removeDelegate.accept(consent);
    }

    @Override
    public @Nullable OAuth2AuthorizationConsent findById(String registeredClientId, String principalName) {
        return this.findByIdDelegate.apply(registeredClientId, principalName);
    }
}
