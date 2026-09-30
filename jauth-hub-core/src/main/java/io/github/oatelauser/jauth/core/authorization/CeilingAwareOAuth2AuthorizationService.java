package io.github.oatelauser.jauth.core.authorization;

import io.github.oatelauser.jauth.core.client.ClientOwner;
import io.github.oatelauser.jauth.core.client.ClientOwnerResolver;
import io.github.oatelauser.jauth.core.org.OrgMembership;
import io.github.oatelauser.jauth.core.org.OrgScopeGate;
import io.github.oatelauser.jauth.core.response.JauthErrorCode;
import io.github.oatelauser.jauth.core.response.JauthException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * ceiling 取交强制（SPEC §3 防线核心，B9）：组织客户端发行 scopes = authorizedScopes ∩
 * installation.ceilingScopes，在授权服务的<b>创建保存</b>上运行时取交；个人/平台客户端不剪（不装、不封顶）。
 *
 * <p><b>创建保存的判定</b>：access token 尚无（{@code getAccessToken() == null}）且 authorizedScopes 非空。
 * 框架四条创建路径恰好落在此形状——授权码 consent 提交保存、授权码静默再授权保存、设备码 consent 提交与
 * 静默验证保存（带 code/user_code、无 access token）；其余保存直通——consent 中转保存（scope 未定）、设备发起
 * 保存（scopes 在 scope 属性、authorizedScopes 为空）、换令牌/刷新/撤销（access token 已在场）。
 * 幂等：authorizedScopes 已 ⊆ ceiling 就原样直通不重建。
 *
 * <p><b>org 上下文解析</b>（口径单点在 {@link OrgScopeGate}）：principal name → 候选 org 集 =
 * membership ∩ APPROVED 安装。<b>0 个 → fail-closed</b>（组织客户端必须先安装；consent 页渲染层本就拦，
 * 这是防手造 POST 的纵深防御）；1 个 → 用之；&gt;1 → 读会话暂存（consent 页选择器写入
 * {@value #CONSENT_ORG_SESSION_KEY_PREFIX}&lt;state&gt;，本装饰器经 RequestContextHolder 取当前请求的
 * state 参数——创建保存发生在请求线程，且 consent 提交 POST 原样回传 state；授权对象上的 STATE 属性在
 * code 生成保存时已被框架移除，故以请求参数为准），无有效暂存同样 fail-closed——多 org 静默重授权 v1.1
 * 不支持。<b>升级路径</b>：多 org 记忆化选择或安装面显式绑定后可放开。
 *
 * <p><b>装配位置</b>：装饰在链<b>最内层紧贴 base</b>（memory：Auditing(FamilyAware(Ceiling(base)))；
 * jdbc：Auditing(PatAware(Ceiling(JauthJdbc(...))))）——外层审计/PAT/家族必须观察到剪后状态。fail-closed
 * 抛 {@link JauthException}（A0508），经框架过滤器对客户端表现为服务端错误——拒绝即目的，文案归宿主异常面。
 *
 * <p><b>不覆盖面</b>：client_credentials 等客户端主体grant 的保存自带 access token，直通不剪（ceiling
 * 语义面向用户授权的组织客户端，客户端主体的封顶留后续按需定）。
 *
 * @author oatelauser
 */
public class CeilingAwareOAuth2AuthorizationService implements OAuth2AuthorizationService {

    /** consent 页 org 选择器的会话暂存键前缀（写入方：ConsentController；完整键 = 前缀 + state）。 */
    public static final String CONSENT_ORG_SESSION_KEY_PREFIX = "jauth.consent.org::";

    /**
     * 委托面取 JDK 函数捕获（构造参数的框架接口不存成员,方法引用拆成四个函数字段——与
     * AuditingOAuth2AuthorizationService 同款取舍,SpotBugs EI_EXPOSE_REP2 洁净;也钉死装饰面只经四个入口）。
     */
    private final Consumer<OAuth2Authorization> saveDelegate;

    private final Consumer<OAuth2Authorization> removeDelegate;

    private final Function<String, @Nullable OAuth2Authorization> findByIdDelegate;

    private final BiFunction<String, OAuth2TokenType, @Nullable OAuth2Authorization> findByTokenDelegate;

    private final ClientOwnerResolver ownerResolver;

    private final OrgScopeGate orgScopeGate;

    public CeilingAwareOAuth2AuthorizationService(
            OAuth2AuthorizationService delegate, ClientOwnerResolver ownerResolver, OrgScopeGate orgScopeGate) {
        Assert.notNull(delegate, "delegate cannot be null");
        Assert.notNull(ownerResolver, "ownerResolver cannot be null");
        Assert.notNull(orgScopeGate, "orgScopeGate cannot be null");
        this.saveDelegate = delegate::save;
        this.removeDelegate = delegate::remove;
        this.findByIdDelegate = delegate::findById;
        this.findByTokenDelegate = delegate::findByToken;
        this.ownerResolver = ownerResolver;
        this.orgScopeGate = orgScopeGate;
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        this.saveDelegate.accept(prunedIfOrgClientCreation(authorization));
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        this.removeDelegate.accept(authorization);
    }

    @Override
    public @Nullable OAuth2Authorization findById(String id) {
        return this.findByIdDelegate.apply(id);
    }

    @Override
    public @Nullable OAuth2Authorization findByToken(String token, @Nullable OAuth2TokenType tokenType) {
        return this.findByTokenDelegate.apply(token, tokenType);
    }

    /** 创建保存且组织客户端才剪，其余直通原对象（幂等分支也直通，调用方可 assertSame 验证未重建）。 */
    private OAuth2Authorization prunedIfOrgClientCreation(OAuth2Authorization authorization) {
        if (authorization.getAccessToken() != null
                || authorization.getAuthorizedScopes().isEmpty()) {
            return authorization;
        }
        ClientOwner owner = this.ownerResolver.findOwner(authorization.getRegisteredClientId());
        if (owner == null || owner.orgId() == null) {
            // 平台/个人/未登记客户端：不装、不封顶（SPEC §3），原样直通
            return authorization;
        }
        List<OrgMembership> candidates =
                this.orgScopeGate.approvedOrgs(authorization.getPrincipalName(), authorization.getRegisteredClientId());
        String orgId = resolveOrgContext(candidates);
        Set<String> ceiling = this.orgScopeGate.ceilingScopes(authorization.getRegisteredClientId(), orgId);
        if (ceiling == null) {
            // 候选判定与 ceiling 读取之间安装被撤销的竞态：按无安装处理，fail-closed
            throw new JauthException(JauthErrorCode.A0508);
        }
        Set<String> authorizedScopes = authorization.getAuthorizedScopes();
        if (ceiling.containsAll(authorizedScopes)) {
            return authorization;
        }
        Set<String> issuedScopes =
                authorizedScopes.stream().filter(ceiling::contains).collect(Collectors.toUnmodifiableSet());
        return OAuth2Authorization.from(authorization)
                .authorizedScopes(issuedScopes)
                .build();
    }

    /** 0 候选 fail-closed；1 候选用之；多候选取会话暂存，无有效暂存同样 fail-closed（类注释升级路径）。 */
    private String resolveOrgContext(List<OrgMembership> candidates) {
        if (candidates.isEmpty()) {
            throw new JauthException(JauthErrorCode.A0508);
        }
        if (candidates.size() == 1) {
            return candidates.get(0).orgId();
        }
        String stashed = stashedOrgSelection(candidates);
        if (stashed != null) {
            return stashed;
        }
        throw new JauthException(JauthErrorCode.A0508);
    }

    /** 会话暂存读取：当前请求 state 参数 → session[前缀+state]，且暂存 org 必须仍在候选集内。 */
    private @Nullable String stashedOrgSelection(List<OrgMembership> candidates) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servletAttributes)) {
            return null;
        }
        HttpServletRequest request = servletAttributes.getRequest();
        String state = request.getParameter(OAuth2ParameterNames.STATE);
        if (!StringUtils.hasText(state)) {
            return null;
        }
        HttpSession session = request.getSession(false);
        if (session == null
                || !(session.getAttribute(CONSENT_ORG_SESSION_KEY_PREFIX + state) instanceof String stashedOrgId)) {
            return null;
        }
        boolean stillCandidate =
                candidates.stream().anyMatch(membership -> membership.orgId().equals(stashedOrgId));
        if (!stillCandidate) {
            return null;
        }
        // state 一次性：读到即焚，防长会话下暂存条目无限累积（匿名不了任何人，纯占内存）
        session.removeAttribute(CONSENT_ORG_SESSION_KEY_PREFIX + state);
        return stashedOrgId;
    }
}
