package io.github.oatelauser.jauth.starter;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.github.oatelauser.jauth.core.audit.AuditEventPublisher;
import io.github.oatelauser.jauth.core.audit.InMemoryRollingAuditService;
import io.github.oatelauser.jauth.core.audit.JdbcAuditEventService;
import io.github.oatelauser.jauth.core.audit.SecurityEventAuditBridge;
import io.github.oatelauser.jauth.core.authorization.AuditingOAuth2AuthorizationConsentService;
import io.github.oatelauser.jauth.core.authorization.AuditingOAuth2AuthorizationService;
import io.github.oatelauser.jauth.core.authorization.CeilingAwareOAuth2AuthorizationService;
import io.github.oatelauser.jauth.core.authorization.JauthJdbcOAuth2AuthorizationService;
import io.github.oatelauser.jauth.core.client.ClientOwnerResolver;
import io.github.oatelauser.jauth.core.client.ClientSeedProperties;
import io.github.oatelauser.jauth.core.client.ClientSeeder;
import io.github.oatelauser.jauth.core.client.InMemoryClientOwnerResolver;
import io.github.oatelauser.jauth.core.client.JauthJdbcRegisteredClientRepository;
import io.github.oatelauser.jauth.core.client.JdbcClientOwnerResolver;
import io.github.oatelauser.jauth.core.org.InMemoryInstallationRepository;
import io.github.oatelauser.jauth.core.org.InMemoryOrgRepository;
import io.github.oatelauser.jauth.core.org.InstallationRepository;
import io.github.oatelauser.jauth.core.org.InstallationService;
import io.github.oatelauser.jauth.core.org.JdbcInstallationRepository;
import io.github.oatelauser.jauth.core.org.JdbcOrgRepository;
import io.github.oatelauser.jauth.core.org.OrgRepository;
import io.github.oatelauser.jauth.core.org.OrgScopeGate;
import io.github.oatelauser.jauth.core.org.OrgService;
import io.github.oatelauser.jauth.core.org.OrgsClaimsContributor;
import io.github.oatelauser.jauth.core.passkey.InMemoryPasskeyCredentialRepository;
import io.github.oatelauser.jauth.core.passkey.JauthUserEntityRepository;
import io.github.oatelauser.jauth.core.passkey.JdbcPasskeyCredentialRepository;
import io.github.oatelauser.jauth.core.ratelimit.RateLimiter;
import io.github.oatelauser.jauth.core.response.DefaultResponseRenderer;
import io.github.oatelauser.jauth.core.response.JauthResponseAdvice;
import io.github.oatelauser.jauth.core.response.ResponseRenderer;
import io.github.oatelauser.jauth.core.scope.InMemoryScopeCatalog;
import io.github.oatelauser.jauth.core.scope.ScopeCatalog;
import io.github.oatelauser.jauth.core.scope.ScopeDefinition;
import io.github.oatelauser.jauth.core.token.ClaimsContributor;
import io.github.oatelauser.jauth.core.token.DefaultClaimsContributor;
import io.github.oatelauser.jauth.core.token.InMemoryTokenFamilyService;
import io.github.oatelauser.jauth.core.token.JdbcTokenFamilyService;
import io.github.oatelauser.jauth.core.token.OidcIdTokenCustomizer;
import io.github.oatelauser.jauth.core.token.OpaqueAccessTokenCustomizer;
import io.github.oatelauser.jauth.core.token.key.JauthJwkSource;
import io.github.oatelauser.jauth.core.token.key.JdbcJwkRepository;
import io.github.oatelauser.jauth.core.token.key.JwkRotationService;
import io.github.oatelauser.jauth.core.user.InMemoryUserRepository;
import io.github.oatelauser.jauth.core.user.JdbcUserRepository;
import io.github.oatelauser.jauth.core.user.SudoGate;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.web.AccessTokenPlatformTokenResolver;
import io.github.oatelauser.jauth.core.web.ConsentController;
import io.github.oatelauser.jauth.core.web.DeviceVerifyController;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import io.github.oatelauser.jauth.core.web.LocalIntrospectionJwtDecoder;
import io.github.oatelauser.jauth.core.web.LoginController;
import io.github.oatelauser.jauth.core.web.LoginLockoutFilter;
import io.github.oatelauser.jauth.core.web.MeController;
import io.github.oatelauser.jauth.core.web.PasskeyFlag;
import io.github.oatelauser.jauth.core.web.PlatformTokenResolver;
import io.github.oatelauser.jauth.core.web.RequiresScope;
import io.github.oatelauser.jauth.core.web.RequiresScopeInterceptor;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.context.MessageSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.authorization.autoconfigure.servlet.OAuth2AuthorizationServerAutoConfiguration;
import org.springframework.context.MessageSource;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2RefreshTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.accept.ContentNegotiationStrategy;
import org.springframework.web.accept.HeaderContentNegotiationStrategy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.templatemode.TemplateMode;

/**
 * jauth-hub 接管式自动配置（SPEC §2 决议 2：引入 starter 即 jauth 装配链生效，Boot 4 自带配置让位）。
 *
 * <p><b>条件装配总览</b>（每条的理由）：
 * <ul>
 * <li>整类先于 Boot 的 {@link MessageSourceAutoConfiguration}（i18n 复合源须抢在默认 messageSource
 * 之前注册，见 {@code jauthMessageSource}）与 {@link ServletWebSecurityAutoConfiguration}/{@link
 * OAuth2AuthorizationServerAutoConfiguration}（先于 Boot 默认安全链求值，令其条件装配观察到 jauth 链
 * 已在场而让位）求值。
 * <li>存储双实现按 {@code jauth-hub.storage} 条件注册（决议 3）：memory 默认（matchIfMissing，零依赖
 * 首跑）、jdbc 显式选择（宿主必须供 DataSource，嵌入契约决议 4）；非法取值两配置皆不装载，上下文以缺
 * bean 启动失败——fail-fast 优于静默回退到错误模式。
 * <li>一切可替换组件（PasswordEncoder、ResponseRenderer、MessageSource、CorsConfigurationSource、各仓储）
 * 走 ConditionalOnMissingBean（决议 4：宿主优先，逐个可替换）。
 * </ul>
 *
 * <p><b>宿主链共存四规则之三（禁止违反）</b>：本配置只产出一条精确匹配的协议链（securityMatcher =
 * 框架协议端点 ∪ /login、/oauth2/consent、/device/verify、core 静态 css），<b>绝不写 anyRequest
 * 兜底</b>——嵌入模式的 default 链是宿主自己的事；jauth 链若吞下未认领请求，宿主接口会被静默纳入 jauth
 * 的认证语义，属结构性越权。
 *
 * @author oatelauser
 */
@AutoConfiguration(
        before = {
            MessageSourceAutoConfiguration.class,
            ServletWebSecurityAutoConfiguration.class,
            OAuth2AuthorizationServerAutoConfiguration.class
        })
@EnableConfigurationProperties({JauthHubProperties.class, MessageSourceProperties.class})
public class JauthHubAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JauthHubAutoConfiguration.class);

    /** 协议链上自认领的页面路径（登录页 = formLogin 落点）。 */
    static final String LOGIN_PATH = "/login";

    /** consent 页（框架 authorizationEndpoint.consentPage 落点，B3 ConsentController）。 */
    static final String CONSENT_PAGE_PATH = "/oauth2/consent";

    /** 设备验证页：AuthorizationServerSettings.deviceVerificationEndpoint 落点（verification_uri 即此）。 */
    static final String DEVICE_VERIFY_PATH = "/device/verify";

    /** 平台 API /me（SPEC §4 端点三分；链 matcher 认领 + 控制器自担 Bearer 认证）。 */
    static final String ME_PATH = "/me";

    /** core 单文件 CSS 的出网路径（模板内 @{/css/jauth.css}）。 */
    static final String CSS_PATTERN = "/css/**";

    /** WebAuthn 端点根（passkey 开启时认领：注册/选项端点、框架默认注册页与其静态资源都在其下）。 */
    static final String WEBAUTHN_PATTERN = "/webauthn/**";

    /** passkey 登录端点（框架 WebAuthnAuthenticationFilter；独立于 /login 表单提交路径，须单独认领）。 */
    static final String WEBAUTHN_LOGIN_PATH = "/login/webauthn";

    /** passkey 认证选项端点（挑战下发是登录第一步，permitAll）。 */
    static final String WEBAUTHN_AUTH_OPTIONS_PATH = "/webauthn/authenticate/options";

    /** core 资源命名空间根（模板/i18n/静态资源与 Flyway 脚本同居其下，防撞宿主同名资源）。 */
    static final String CORE_NAMESPACE = "io/github/oatelauser/jauth/core";

    static final String CORE_TEMPLATES_PREFIX = "classpath:" + CORE_NAMESPACE + "/web/templates/";

    static final String CORE_I18N_BASENAME = CORE_NAMESPACE + "/i18n/messages";

    static final String CORE_CSS_LOCATION = "classpath:" + CORE_NAMESPACE + "/web/static/css/";

    /** Flyway 私有历史表：与宿主自己的 flyway_schema_history 并存不撞（SPEC §2 库表归属的装配侧实现）。 */
    static final String FLYWAY_HISTORY_TABLE = "jauth_flyway_schema_history";

    // ------------------------------------------------------------------ 基础组件

    /**
     * 密码编码器：bcrypt 默认强度（SPEC §6）。种子 client_secret 的编码消费它，宿主可整体替换（决议 4）。
     */
    @Bean
    @ConditionalOnMissingBean
    PasswordEncoder jauthPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // ------------------------------------------------------------------ 响应 SPI（SPEC §4）

    /**
     * 默认渲染器：SimpleResponse 形状的裸 JSON 三段。spring-plus-web 在场时由桥自动配置注册
     * SimpleResponse 版并使本默认让位（桥 @AutoConfigureBefore 本类，见桥类注释）。
     */
    @Bean
    @ConditionalOnMissingBean(ResponseRenderer.class)
    ResponseRenderer defaultResponseRenderer() {
        return new DefaultResponseRenderer();
    }

    /** jauth 自有 advice：只接 JauthException，不包装协议端点（core 类注释有边界论证）。 */
    @Bean
    @ConditionalOnMissingBean
    JauthResponseAdvice jauthResponseAdvice(ResponseRenderer renderer) {
        return new JauthResponseAdvice(renderer);
    }

    // ------------------------------------------------------------------ 限流与登录锁定（SPEC §5/§6）

    /**
     * 限流器（内存计数器无表，票 07）：请求配额按调用方主体合并桶；登录锁定按用户名键控。
     * Clock 宿主可注入（jdbc 模式默认 systemUTC），测试以可变钟推进窗口/锁定期。
     */
    @Bean
    @ConditionalOnMissingBean
    RateLimiter jauthRateLimiter(JauthHubProperties properties, ObjectProvider<Clock> clock) {
        return new RateLimiter(
                properties.getRateLimit().getLimitPerHour(),
                properties.getRateLimit().getLoginMaxFailures(),
                Duration.ofMinutes(properties.getRateLimit().getLoginLockMinutes()),
                clock.getIfAvailable(Clock::systemUTC));
    }

    // ------------------------------------------------------------------ 审计与 /me（SPEC §5 横切）

    /**
     * Spring Security 认证事件接线桥：登录成功/失败 → 审计 + 登录失败锁定计数 + passkey 成功的强认证
     * 打点（core 类注释的事件源过滤论证）。audit publisher 与 UserRepository 按存储模式由两个配置分支供给；
     * Clock 照限流器先例 ObjectProvider 可缺省（jdbc 模式默认 systemUTC，测试可注可变钟）。
     */
    @Bean
    SecurityEventAuditBridge jauthSecurityEventAuditBridge(
            AuditEventPublisher auditPublisher,
            RateLimiter rateLimiter,
            UserRepository userRepository,
            ObjectProvider<Clock> clock) {
        return new SecurityEventAuditBridge(
                auditPublisher, rateLimiter, userRepository, clock.getIfAvailable(Clock::systemUTC));
    }

    /**
     * sudo 判官（v1.2 C3）：仅 {@code jauth-hub.sudo.enabled=true} 注册（默认关 = 零 bean 零拦截）。
     * SPEC §5：sudo 依赖 passkey——passkey 关而 sudo 开是配置矛盾（强认证无因子来源，验证页也无处可跳），
     * 启动 fail-fast 不静默放行。
     */
    @Bean
    @ConditionalOnProperty(name = "jauth-hub.sudo.enabled", havingValue = "true")
    @ConditionalOnMissingBean(SudoGate.class)
    SudoGate jauthSudoGate(JauthHubProperties properties, UserRepository userRepository, ObjectProvider<Clock> clock) {
        if (!properties.getPasskey().isEnabled()) {
            throw new IllegalStateException("jauth-hub.sudo.enabled=true requires jauth-hub.passkey.enabled=true: "
                    + "sudo mode re-verifies the user with a passkey, without it there is no strong-auth factor");
        }
        return new SudoGate(
                userRepository,
                Duration.ofMinutes(properties.getSudo().getTtlMinutes()),
                clock.getIfAvailable(Clock::systemUTC));
    }

    /** /me 平台端点（core）：认证面 = PlatformTokenResolver 本进程内省（SPEC §4）。 */
    @Bean
    MeController jauthMeController(PlatformTokenResolver tokenResolver) {
        return new MeController(tokenResolver);
    }

    // ------------------------------------------------------------------ Passkey 强化层（SPEC §5 v1.2，默认关）

    /**
     * WebAuthn 用户句柄仓储（storage 无关，只依赖 UserRepository；凭据仓储按存储模式在下方两段供给）。
     * 仅 passkey 开启时注册；WebAuthnConfigurer 按 bean 类型自动发现（容器内有即用，否则退框架内存版）。
     */
    @Bean
    @ConditionalOnProperty(name = "jauth-hub.passkey.enabled", havingValue = "true")
    @ConditionalOnMissingBean(PublicKeyCredentialUserEntityRepository.class)
    JauthUserEntityRepository jauthUserEntityRepository(UserRepository userRepository) {
        return new JauthUserEntityRepository(userRepository);
    }

    // ------------------------------------------------------------------ org 域装配（B8）

    /**
     * org 域服务（自助创建 + OWNER 门）：仓储由下方两段 storage 配置按 {@code jauth-hub.storage} 供给
     * （memory/jdbc 皆注册 OrgRepository，此处单点装配）。事务模板经 provider 取"在场即用"——jdbc 模式命中
     * jauthSeedingTransactionTemplate（或宿主自带模板，既定让位语义），memory 模式无模板 bean 传 null 直通
     * （B10 滑账①收口，OrgService 类注释）。
     */
    @Bean
    @ConditionalOnMissingBean
    OrgService jauthOrgService(
            OrgRepository orgRepository,
            AuditEventPublisher auditPublisher,
            ObjectProvider<Clock> clock,
            ObjectProvider<TransactionTemplate> transactionTemplate) {
        return new OrgService(
                orgRepository,
                auditPublisher,
                clock.getIfAvailable(Clock::systemUTC),
                transactionTemplate.getIfAvailable());
    }

    /** 安装两步制（流向 A）：request/approve/reject/revoke 状态机 + 生命周期审计。 */
    @Bean
    @ConditionalOnMissingBean
    InstallationService jauthInstallationService(
            InstallationRepository installationRepository,
            OrgRepository orgRepository,
            OrgService orgService,
            RegisteredClientRepository registeredClientRepository,
            AuditEventPublisher auditPublisher,
            ObjectProvider<Clock> clock) {
        return new InstallationService(
                installationRepository,
                orgRepository,
                orgService,
                registeredClientRepository,
                auditPublisher,
                clock.getIfAvailable(Clock::systemUTC));
    }

    /**
     * ceiling 封门口径（B9）：候选 org 集 / ceiling 读取的单点查询，服务端强制（授权服务链的 CeilingAware
     * 装饰器）与 consent 页 org 上下文共用；仓储三件由两段 storage 供给，此处单点装配。
     */
    @Bean
    @ConditionalOnMissingBean
    OrgScopeGate jauthOrgScopeGate(
            UserRepository userRepository, OrgRepository orgRepository, InstallationRepository installationRepository) {
        return new OrgScopeGate(userRepository, orgRepository, installationRepository);
    }

    // ------------------------------------------------------------------ 令牌装配

    /** 默认 claims 贡献者：sub/username，用户查找接 jauth UserRepository（B2 既定接线）。 */
    @Bean
    @ConditionalOnMissingBean(ClaimsContributor.class)
    DefaultClaimsContributor defaultClaimsContributor(UserRepository userRepository) {
        return new DefaultClaimsContributor(userRepository::findByUsername);
    }

    /**
     * orgs claims 贡献者（B9）：claim orgs = 用户全部 org 归属（id/name/role），经下方两个定制器同形接出
     * id_token / userinfo / opaque 内省。声明在默认贡献者之后：默认贡献者的让位条件以宿主自定义为准，
     * 本贡献者独立在场（条件钉自身类型，不因宿主替换 sub/username 贡献者而消失）。
     */
    @Bean
    @ConditionalOnMissingBean(OrgsClaimsContributor.class)
    OrgsClaimsContributor orgsClaimsContributor(UserRepository userRepository, OrgRepository orgRepository) {
        return new OrgsClaimsContributor(userRepository::findByUsername, orgRepository::findMembershipsByUser);
    }

    /** opaque access token 定制器（消费面一：内省富化），按 List 注入宿主可追加的贡献者。 */
    @Bean
    OpaqueAccessTokenCustomizer opaqueAccessTokenCustomizer(List<ClaimsContributor> contributors) {
        return new OpaqueAccessTokenCustomizer(contributors);
    }

    /** OIDC id_token 定制器（消费面二：userinfo 同源富化）。 */
    @Bean
    OidcIdTokenCustomizer oidcIdTokenCustomizer(List<ClaimsContributor> contributors) {
        return new OidcIdTokenCustomizer(contributors);
    }

    /**
     * 令牌生成器组合：Jwt 生成器（id_token 必需；access token 仅对 TokenSettings 声明 SELF_CONTAINED 的
     * 客户端生效）+ opaque 生成器（REFERENCE 格式，v1 正典形态：opaque + 内省，SPEC §5）+ 刷新令牌生成器。
     * 格式由 client 注册的 TokenSettings 决定（§6 策略表 per-client 可覆盖），装配层不替宿主二选一。
     */
    @Bean
    OAuth2TokenGenerator<? extends OAuth2Token> jauthTokenGenerator(
            JWKSource<SecurityContext> jwkSource,
            OpaqueAccessTokenCustomizer accessTokenCustomizer,
            OidcIdTokenCustomizer idTokenCustomizer) {
        OAuth2AccessTokenGenerator accessTokenGenerator = new OAuth2AccessTokenGenerator();
        accessTokenGenerator.setAccessTokenCustomizer(accessTokenCustomizer);
        JwtGenerator jwtGenerator = new JwtGenerator(new NimbusJwtEncoder(jwkSource));
        jwtGenerator.setJwtCustomizer(idTokenCustomizer);
        return new DelegatingOAuth2TokenGenerator(
                jwtGenerator, accessTokenGenerator, new OAuth2RefreshTokenGenerator());
    }

    /** 授权服务器设置：issuer 可配；deviceVerificationEndpoint 落自有页面（框架默认 /oauth2/device_verification）。 */
    @Bean
    @ConditionalOnMissingBean
    AuthorizationServerSettings jauthAuthorizationServerSettings(JauthHubProperties properties) {
        return AuthorizationServerSettings.builder()
                .issuer(properties.getIssuer())
                .deviceVerificationEndpoint(DEVICE_VERIFY_PATH)
                .build();
    }

    /**
     * JwtDecoder：Security 7 的 oidc() 装配在 build 期硬性要求容器内有 JwtDecoder（userinfo 端点的资源服务器
     * 侧接线）。本装配给<b>本进程内省优先</b>版（B7）：先经 {@link PlatformTokenResolver} 解析 opaque 授权
     * 令牌与 PAT（/userinfo、/me 等 Bearer 面），未命中回落真 JWT 解码（同一 JWKSource 派生，即框架
     * OAuth2AuthorizationServerConfiguration.jwtDecoder 的公开工厂；SELF_CONTAINED 客户端仍走签名验证），
     * 宿主可整体替换。框架因 oidc userinfo 强制挂 JWT 腿且自动消费本 bean——以 bean 形态接入而非
     * oauth2ResourceServer DSL，避免与宿主/rs-starter 的 opaque 装配在同名配置器上冲突
     * （"JWTs or Opaque Tokens, not both"）。
     */
    @Bean
    @ConditionalOnMissingBean
    JwtDecoder jauthJwtDecoder(JWKSource<SecurityContext> jwkSource, PlatformTokenResolver platformTokenResolver) {
        return new LocalIntrospectionJwtDecoder(
                platformTokenResolver, OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource));
    }

    // ------------------------------------------------------------------ 页面与教学（SPEC §7）

    /** 教学层开关：属性绑定（默认开），core 三页消费。 */
    @Bean
    EducationalFlag jauthEducationalFlag(JauthHubProperties properties) {
        return properties::isEducational;
    }

    /** passkey 可见性开关（v1.2 C2）：属性绑定（SPEC §5 默认关），core 登录页与 selfservice 看板导航消费。 */
    @Bean
    PasskeyFlag jauthPasskeyFlag(JauthHubProperties properties) {
        return properties.getPasskey()::isEnabled;
    }

    /** scope 目录：内存实现内置三枚 OIDC 标准 scope，宿主可注册自有 scope（目录 = 代码 + i18n，不建表）。 */
    @Bean
    @ConditionalOnMissingBean(ScopeCatalog.class)
    ScopeCatalog jauthScopeCatalog() {
        return new InMemoryScopeCatalog();
    }

    /**
     * {@code @RequiresScope} 自动注册（v1.2 C4 ①）：启动扫同 JVM 全量 HandlerMethod，注解声明的 scope
     * 幂等进目录（同名 upsert，宿主显式注册仍可覆盖）。SmartInitializingSingleton 在全部单例就位后、
     * ApplicationRunner（播种等）之前回调——注册先于任何启动消费者完成。经 provider 流式取映射：
     * 按类型解析会撞 actuator 的 controllerEndpointHandlerMapping（RequestMappingHandlerMapping 子类，
     * 令 by-type 唯一性破坏），流式遍历全部映射即覆盖宿主 MVC 全域；非 WebMvc 宿主（装配矩阵的裸
     * runner 面）无映射时为空流，静默无操作而非启动失败。
     */
    @Bean
    SmartInitializingSingleton jauthRequiresScopeRegistrar(
            ObjectProvider<RequestMappingHandlerMapping> handlerMappings, ScopeCatalog scopeCatalog) {
        return () -> handlerMappings.stream()
                .forEach(mapping -> mapping.getHandlerMethods().values().forEach(handlerMethod -> {
                    RequiresScope requiresScope = handlerMethod.getMethodAnnotation(RequiresScope.class);
                    if (requiresScope == null) {
                        return;
                    }
                    // desc 空串归一为 null：consent 页兜底序回退裸名，目录内不落""哨兵值
                    scopeCatalog.register(ScopeDefinition.of(
                            requiresScope.value(),
                            requiresScope.sensitive(),
                            requiresScope.desc().isEmpty() ? null : requiresScope.desc()));
                    log.info(
                            "[jauth-hub] @RequiresScope registered scope '{}' (sensitive={})",
                            requiresScope.value(),
                            requiresScope.sensitive());
                }));
    }

    /**
     * {@code @RequiresScope} 声明式校验（v1.2 C4 ②）：全局注册（照 jauthStaticResourcesConfigurer 形态）。
     * 无开关——拦截器对非注解方法只有一次 instanceof 判定，注解不挂即零行为，不改变任何既有请求路径。
     */
    @Bean
    WebMvcConfigurer jauthRequiresScopeInterceptorConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new RequiresScopeInterceptor());
            }
        };
    }

    @Bean
    LoginController jauthLoginController(EducationalFlag educational, PasskeyFlag passkey) {
        return new LoginController(educational, passkey);
    }

    @Bean
    ConsentController jauthConsentController(
            RegisteredClientRepository clientRepository,
            ScopeCatalog scopeCatalog,
            MessageSource messageSource,
            EducationalFlag educational,
            OrgScopeGate orgScopeGate,
            ClientOwnerResolver clientOwnerResolver) {
        return new ConsentController(
                clientRepository, scopeCatalog, messageSource, educational, orgScopeGate, clientOwnerResolver);
    }

    @Bean
    DeviceVerifyController jauthDeviceVerifyController(EducationalFlag educational) {
        return new DeviceVerifyController(educational);
    }

    /**
     * i18n 复合源：core 命名空间 basename + 宿主 spring.messages.* 中真实可解析的 basename 合并为一个
     * ResourceBundleMessageSource。必须在 Boot 的 MessageSourceAutoConfiguration <b>之前</b>求值（类注解
     * before）以 messageSource 之名注册：否则 Boot 先建宿主单源、core 文案（jauth.* 键）在 Thymeleaf
     * （#{...} 走 context messageSource）与 ConsentController 均无解析处；Boot 侧因同名 bean 已存在整体
     * 让位，宿主 spring.messages.* 配置由本复合源如实承接。宿主自定义 messageSource bean 时本让位
     * （jauth 模板文案归宿主自理）。
     */
    @Bean(name = "messageSource")
    @ConditionalOnMissingBean(name = "messageSource")
    MessageSource jauthMessageSource(MessageSourceProperties properties) {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setDefaultEncoding(properties.getEncoding().name());
        messageSource.setFallbackToSystemLocale(properties.isFallbackToSystemLocale());
        Duration cacheDuration = properties.getCacheDuration();
        if (cacheDuration != null) {
            messageSource.setCacheMillis(cacheDuration.toMillis());
        }
        List<String> basenames = new ArrayList<>();
        basenames.add(CORE_I18N_BASENAME);
        for (String basename : properties.getBasename()) {
            // 只并入真实可解析的 basename：默认 "messages" 在多数宿主不存在，盲并入会每次解析刷 WARN
            if (bundleResolvable(basename)) {
                basenames.add(basename);
            }
        }
        messageSource.setBasenames(basenames.toArray(String[]::new));
        return messageSource;
    }

    private static boolean bundleResolvable(String basename) {
        try {
            ResourceBundle.getBundle(basename, Locale.ROOT, JauthHubAutoConfiguration.class.getClassLoader());
            return true;
        } catch (MissingResourceException ex) {
            return false;
        }
    }

    /**
     * core 模板解析器：前缀指 core 命名空间目录（防撞宿主 templates/）。checkExistence + 最高序位——
     * 仅 core 视图名（login/consent/device-verify）命中，未命中即穿透到宿主默认解析器，宿主自有视图不受扰；
     * 宿主要覆盖 jauth 页面：以<b>相同资源路径</b>放置同名文件（classpath 资源遮蔽，确定性生效）。
     */
    @Bean
    SpringResourceTemplateResolver jauthTemplateResolver() {
        SpringResourceTemplateResolver resolver = new SpringResourceTemplateResolver();
        resolver.setPrefix(CORE_TEMPLATES_PREFIX);
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCheckExistence(true);
        resolver.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return resolver;
    }

    /** core 单文件 CSS 出网（/css/jauth.css → core 命名空间）；宿主自有 /css/** 静态资源不受影响（未命中即穿透）。 */
    @Bean
    WebMvcConfigurer jauthStaticResourcesConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addResourceHandlers(ResourceHandlerRegistry registry) {
                registry.addResourceHandler(CSS_PATTERN).addResourceLocations(CORE_CSS_LOCATION);
            }
        };
    }

    /**
     * 协议端点 CORS 源（SPEC §4：仅协议端点）：映射面取 AuthorizationServerSettings 实际端点路径，来源空时
     * 不注册任何映射（惰性）。链侧只在来源非空时启用 .cors()，故默认（空）下 CORS 全关。宿主自定义
     * corsConfigurationSource（服务自家链）时本让位，jauth 链的 CORS 归宿主自理。
     */
    @Bean(name = "corsConfigurationSource")
    @ConditionalOnMissingBean(name = "corsConfigurationSource")
    CorsConfigurationSource jauthCorsConfigurationSource(
            JauthHubProperties properties, AuthorizationServerSettings settings) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (!properties.getCors().getAllowedOrigins().isEmpty()) {
            CorsConfiguration configuration = new CorsConfiguration();
            configuration.setAllowedOrigins(properties.getCors().getAllowedOrigins());
            configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
            configuration.setAllowedHeaders(List.of("*"));
            configuration.setAllowCredentials(true);
            for (String endpoint : protocolEndpointPaths(settings)) {
                source.registerCorsConfiguration(endpoint, configuration);
            }
        }
        return source;
    }

    /** 协议端点路径全集：settings 实际值 + 两个 RFC 发现端点（常量路径不在 settings 内）。 */
    private static List<String> protocolEndpointPaths(AuthorizationServerSettings settings) {
        List<String> paths = new ArrayList<>();
        paths.add(settings.getAuthorizationEndpoint());
        paths.add(settings.getPushedAuthorizationRequestEndpoint());
        paths.add(settings.getTokenEndpoint());
        paths.add(settings.getTokenIntrospectionEndpoint());
        paths.add(settings.getTokenRevocationEndpoint());
        paths.add(settings.getJwkSetEndpoint());
        paths.add(settings.getDeviceAuthorizationEndpoint());
        paths.add(settings.getDeviceVerificationEndpoint());
        paths.add(settings.getOidcUserInfoEndpoint());
        paths.add("/.well-known/openid-configuration");
        paths.add("/.well-known/oauth-authorization-server");
        return paths;
    }

    // ------------------------------------------------------------------ 播种（SPEC §2 决议 3）

    /**
     * 启动播种：properties → 活动仓库，memory/jdbc 两模式通用（core ClientSeeder 只依赖仓库接口，幂等）。
     * jdbc 模式若容器内有 TransactionTemplate（宿主有事务管理器即有）则整轮播种入事务——core
     * JauthJdbcRegisteredClientRepository 的 owner 两列同步与主 INSERT 两条语句非原子（B1 遗留 Medium：
     * 无事务时崩溃可留 owner 为 NULL 的行），事务包裹使两步同成同败，就地收口；memory 模式无事务概念直跑。
     */
    @Bean
    ApplicationRunner jauthClientSeederRunner(
            JauthHubProperties properties,
            PasswordEncoder passwordEncoder,
            RegisteredClientRepository registeredClientRepository,
            ObjectProvider<TransactionTemplate> transactionTemplate) {
        return args -> {
            ClientSeedProperties seedProperties = new ClientSeedProperties();
            seedProperties.setClients(properties.getClients());
            ClientSeeder seeder = new ClientSeeder(seedProperties, passwordEncoder);
            TransactionTemplate transaction = transactionTemplate.getIfAvailable();
            if (transaction != null) {
                transaction.executeWithoutResult(status -> seeder.seed(registeredClientRepository));
            } else {
                seeder.seed(registeredClientRepository);
            }
        };
    }

    // ------------------------------------------------------------------ 协议链（SPEC §2 四规则）

    /**
     * jauth 协议链：唯一产出的安全链，精认知领框架协议端点 ∪ 自有四路径，序位可配（默认 100，委托
     * OrderedSecurityFilterChain 实现）。链内授权规则逐路径显式声明（login/css 放行、协议端点与两页面需
     * 认证），<b>无 anyRequest 兜底</b>（类注释第三规则）。非浏览器客户端（Accept 非 text/html）401 而非
     * 重定向登录页（协议端点的正确姿势）。CORS 仅在来源非空时并入。
     */
    @Bean
    SecurityFilterChain jauthProtocolSecurityFilterChain(
            HttpSecurity http,
            JauthHubProperties properties,
            RegisteredClientRepository registeredClientRepository,
            OAuth2AuthorizationService authorizationService,
            OAuth2AuthorizationConsentService authorizationConsentService,
            AuthorizationServerSettings authorizationServerSettings,
            OAuth2TokenGenerator<? extends OAuth2Token> tokenGenerator,
            RateLimiter rateLimiter)
            throws Exception {

        http.oauth2AuthorizationServer(authorizationServer -> authorizationServer
                .registeredClientRepository(registeredClientRepository)
                .authorizationService(authorizationService)
                .authorizationConsentService(authorizationConsentService)
                .authorizationServerSettings(authorizationServerSettings)
                .tokenGenerator(tokenGenerator)
                .oidc(oidc -> oidc
                        // RP-Initiated Logout 显式钉住框架默认语义（SPEC §5 横切）：id_token_hint 须为本
                        // 主体签发且未吊销，post_logout_redirect_uri 须在客户端注册白名单（精确匹配），
                        // 成功即失效会话并 302 回 RP（可携带 state）——校验语义全在框架 provider，不重写
                        .logoutEndpoint(Customizer.withDefaults()))
                // Device Flow（SPEC §5 v1.0）：框架按惰性启用——不显式调用则 /device_authorization 与
                // 验证端点不挂链（404）；verification_uri 广告值同步落自有页面（框架默认
                // /oauth2/device_verification 只是广告串，不随 AuthorizationServerSettings 联动）
                .deviceAuthorizationEndpoint(
                        deviceAuthorization -> deviceAuthorization.verificationUri(DEVICE_VERIFY_PATH))
                .authorizationEndpoint(endpoint -> endpoint.consentPage(CONSENT_PAGE_PATH)));

        OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
                http.getConfigurer(OAuth2AuthorizationServerConfigurer.class);
        RequestMatcher endpointsMatcher = authorizationServerConfigurer.getEndpointsMatcher();
        List<RequestMatcher> claimedMatchers = new ArrayList<>(List.of(
                endpointsMatcher,
                PathPatternRequestMatcher.withDefaults().matcher(LOGIN_PATH),
                PathPatternRequestMatcher.withDefaults().matcher(CONSENT_PAGE_PATH),
                PathPatternRequestMatcher.withDefaults().matcher(DEVICE_VERIFY_PATH),
                PathPatternRequestMatcher.withDefaults().matcher(ME_PATH),
                PathPatternRequestMatcher.withDefaults().matcher(CSS_PATTERN)));
        if (properties.getPasskey().isEnabled()) {
            // /login 的精确 matcher 不匹配子路径，passkey 登录端点须单独认领（C1）
            claimedMatchers.add(PathPatternRequestMatcher.withDefaults().matcher(WEBAUTHN_PATTERN));
            claimedMatchers.add(PathPatternRequestMatcher.withDefaults().matcher(WEBAUTHN_LOGIN_PATH));
        }
        http.securityMatcher(new OrRequestMatcher(claimedMatchers.toArray(RequestMatcher[]::new)));

        // 登录锁定闸：用户名口令认证之前（锁定期内不触达口令校验）
        http.addFilterBefore(
                new LoginLockoutFilter(rateLimiter, LOGIN_PATH), UsernamePasswordAuthenticationFilter.class);
        // 端点限流：经配置器挂客户端认证之后、端点过滤器之前（配置器类注释的序位论证——锚类 build 期才注册）
        http.with(new RateLimitEndpointConfigurer<>(rateLimiter, authorizationServerSettings));

        // CORS 仅在显式配置来源时启用（默认空 = 关，SPEC §4）
        if (!properties.getCors().getAllowedOrigins().isEmpty()) {
            http.cors(Customizer.withDefaults());
        }

        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers(endpointsMatcher)
                .authenticated()
                .requestMatchers(LOGIN_PATH, CSS_PATTERN, ME_PATH)
                .permitAll()
                .requestMatchers(CONSENT_PAGE_PATH, DEVICE_VERIFY_PATH)
                .authenticated());

        // Passkey 端点授权与 DSL（SPEC §5 v1.2：默认关 = 端点不认领不装配；开时仍逐路径显式，无 anyRequest 兜底）
        if (properties.getPasskey().isEnabled()) {
            http.authorizeHttpRequests(authorize -> authorize
                    .requestMatchers(WEBAUTHN_AUTH_OPTIONS_PATH, WEBAUTHN_LOGIN_PATH)
                    .permitAll()
                    .requestMatchers(WEBAUTHN_PATTERN)
                    .authenticated());
            PasskeyRelyingPartyIdentity rpIdentity = resolvePasskeyRelyingPartyIdentity(properties);
            http.webAuthn(webAuthn -> webAuthn.rpId(rpIdentity.rpId())
                    .rpName(rpIdentity.rpName())
                    .allowedOrigins(rpIdentity.allowedOrigins())
                    // C1 只接 API 地基（POST options/register/login）；框架内置注册页引用的
                    // /default-ui.css、/login/webauthn.js 不在协议链认领范围，留着是一张加载不出
                    // 脚本的残页——禁用之，页面/JS 归 C2 自有 UI
                    .disableDefaultRegistrationPage(true));
        }

        http.formLogin(form -> form.loginPage(LOGIN_PATH).permitAll());

        ContentNegotiationStrategy contentNegotiationStrategy = http.getSharedObject(ContentNegotiationStrategy.class);
        if (contentNegotiationStrategy == null) {
            contentNegotiationStrategy = new HeaderContentNegotiationStrategy();
        }
        MediaTypeRequestMatcher textHtmlMatcher = new MediaTypeRequestMatcher(
                contentNegotiationStrategy, MediaType.APPLICATION_XHTML_XML, MediaType.TEXT_HTML);
        AuthenticationEntryPoint loginEntryPoint = new LoginUrlAuthenticationEntryPoint(LOGIN_PATH);
        http.exceptionHandling(
                exceptions -> exceptions.defaultAuthenticationEntryPointFor(loginEntryPoint, textHtmlMatcher));

        return new OrderedSecurityFilterChain(properties.getFilterChainOrder(), http.build());
    }

    /**
     * passkey RP 三元组解析：显式配置优先，缺口从 {@code jauth-hub.issuer} 推导（host 即 rpId、
     * scheme://host[:port] 即 origin）——issuer 是 jauth 唯一既有的对外基准 URL。issuer 不可解析而 rpId 或
     * origins 仍缺时启动 fail-fast：WebAuthn 的 RP 身份猜错是运行期全量认证失败，静默兜底毫无意义。
     */
    private static PasskeyRelyingPartyIdentity resolvePasskeyRelyingPartyIdentity(JauthHubProperties properties) {
        JauthHubProperties.Passkey passkey = properties.getPasskey();
        String rpId = passkey.getRpId();
        Set<String> allowedOrigins = new LinkedHashSet<>(passkey.getAllowedOrigins());
        String rpName = passkey.getRpName() == null ? "jauth-hub" : passkey.getRpName();
        if (rpId == null || allowedOrigins.isEmpty()) {
            URI issuerUrl = parseIssuerUrl(properties.getIssuer());
            if (issuerUrl == null) {
                throw new IllegalStateException("jauth-hub.passkey.enabled=true requires jauth-hub.passkey.rp-id"
                        + " and allowed-origins (or a parseable jauth-hub.issuer URL to derive them from),"
                        + " but issuer is not a URL with host: " + properties.getIssuer());
            }
            if (rpId == null) {
                rpId = issuerUrl.getHost();
            }
            if (allowedOrigins.isEmpty()) {
                allowedOrigins.add(originOf(issuerUrl));
            }
        }
        return new PasskeyRelyingPartyIdentity(rpId, rpName, allowedOrigins);
    }

    /** issuer 可解析为带 scheme/host 的 URL 则返回，否则 null（调用方 fail-fast）。 */
    private static @Nullable URI parseIssuerUrl(String issuer) {
        try {
            URI uri = URI.create(issuer);
            return uri.getScheme() == null || uri.getHost() == null ? null : uri;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String originOf(URI uri) {
        int port = uri.getPort();
        return uri.getScheme() + "://" + uri.getHost() + (port == -1 ? "" : ":" + port);
    }

    /** 解析后的 WebAuthn RP 三元组（rpId/rpName/allowedOrigins，均已非空）。 */
    private record PasskeyRelyingPartyIdentity(String rpId, String rpName, Set<String> allowedOrigins) {}

    /**
     * 序位可配的安全链包装：SecurityFilterChain 的排序看 Ordered/@Order，注解无法携带运行期属性值，故以薄
     * 委托实现 Ordered 把 {@code jauth-hub.filter-chain-order}（默认 100）带进链排序。
     */
    private static final class OrderedSecurityFilterChain implements SecurityFilterChain, Ordered {

        private final SecurityFilterChain delegate;

        private final int order;

        OrderedSecurityFilterChain(int order, SecurityFilterChain delegate) {
            this.delegate = delegate;
            this.order = order;
        }

        @Override
        public int getOrder() {
            return this.order;
        }

        @Override
        public boolean matches(jakarta.servlet.http.HttpServletRequest request) {
            return this.delegate.matches(request);
        }

        @Override
        public List<jakarta.servlet.Filter> getFilters() {
            return this.delegate.getFilters();
        }
    }

    // ------------------------------------------------------------------ 存储双实现（SPEC §1 决议）

    /**
     * memory 存储（默认，matchIfMissing——零依赖首跑）：框架 InMemory 三件 + core 内存族谱 + 内存用户仓储 +
     * 短命签名密钥。RTR 熔断经 {@link FamilyAwareInMemoryAuthorizationService} 接入（框架内存实现 final
     * 不可子类化，委托包装补族谱语义，与 JDBC 版授权服务行为对齐）。密钥重启即换是 memory 模式既定语义
     * （SPEC §3），不落 jauth_jwk 表、无轮转调度。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(
            name = "jauth-hub.storage",
            havingValue = JauthHubProperties.STORAGE_MEMORY,
            matchIfMissing = true)
    static class MemoryStorageConfiguration {

        /**
         * 内存 client 仓库：框架 InMemoryRegisteredClientRepository 不可空构造（Assert.notEmpty），故
         * memory 模式直接以种子清单构造。种子 → RegisteredClient 的转换<b>复用 core ClientSeeder</b>（录制
         * 仓库收单，不复制转换逻辑，免漂移）；播种 runner 随后再跑一遍为幂等确认（client_id 已存在即跳过）。
         * 空种子在 memory 模式是配置错误：fail-fast，不静默注入占位 client（正式嵌入走 jdbc 模式）。
         */
        @Bean
        @ConditionalOnMissingBean
        InMemoryRegisteredClientRepository jauthRegisteredClientRepository(
                JauthHubProperties properties, PasswordEncoder passwordEncoder) {
            List<RegisteredClient> seeds = convertSeeds(properties, passwordEncoder);
            return new InMemoryRegisteredClientRepository(seeds.toArray(RegisteredClient[]::new));
        }

        /** 种子转换：ClientSeeder 向录制仓库播种，save 即收集（转换与校验逻辑单源于 core）。 */
        private static List<RegisteredClient> convertSeeds(
                JauthHubProperties properties, PasswordEncoder passwordEncoder) {
            ClientSeedProperties seedProperties = new ClientSeedProperties();
            seedProperties.setClients(properties.getClients());
            List<RegisteredClient> recorded = new ArrayList<>();
            RegisteredClientRepository recorder = new RegisteredClientRepository() {
                @Override
                public void save(RegisteredClient registeredClient) {
                    recorded.add(registeredClient);
                }

                @Override
                public @Nullable RegisteredClient findById(String id) {
                    return null;
                }

                @Override
                public @Nullable RegisteredClient findByClientId(String clientId) {
                    return null;
                }
            };
            new ClientSeeder(seedProperties, passwordEncoder).seed(recorder);
            if (recorded.isEmpty()) {
                throw new IllegalStateException(
                        "jauth-hub.storage=memory requires at least one jauth-hub.clients[] seed"
                                + " (framework InMemoryRegisteredClientRepository cannot be constructed empty;"
                                + " use storage=jdbc for host-managed client registration)");
            }
            return recorded;
        }

        @Bean
        @ConditionalOnMissingBean
        InMemoryTokenFamilyService jauthTokenFamilyService() {
            return new InMemoryTokenFamilyService();
        }

        /**
         * memory 审计降级：内存滚动缓冲（SPEC §3 语义——有界、仅调试、重启即失；core 类注释）。
         */
        @Bean
        @ConditionalOnMissingBean(AuditEventPublisher.class)
        InMemoryRollingAuditService jauthAuditEventPublisher(ObjectProvider<Clock> clock) {
            return new InMemoryRollingAuditService(clock.getIfAvailable(Clock::systemUTC));
        }

        /**
         * 授权服务 = 审计装饰（族谱包装版）：RTR 熔断在包装层，生命周期审计（签发/刷新/撤销）在装饰层，
         * 各自单点。ceiling 取交（B9）在最内层紧贴 base——外层审计/家族必须观察到剪后状态。宿主自定义
         * 授权服务时整链让位（含审计装饰——审计接线属 jauth 装配职责，不裹宿主实现）。
         */
        @Bean
        @ConditionalOnMissingBean(OAuth2AuthorizationService.class)
        AuditingOAuth2AuthorizationService jauthAuthorizationService(
                InMemoryTokenFamilyService tokenFamilyService,
                ClientOwnerResolver clientOwnerResolver,
                OrgScopeGate orgScopeGate,
                AuditEventPublisher auditPublisher,
                ObjectProvider<MeterRegistry> meterRegistry) {
            return new AuditingOAuth2AuthorizationService(
                    new FamilyAwareInMemoryAuthorizationService(
                            new CeilingAwareOAuth2AuthorizationService(
                                    new InMemoryOAuth2AuthorizationService(), clientOwnerResolver, orgScopeGate),
                            tokenFamilyService),
                    auditPublisher,
                    meterRegistry.getIfAvailable());
        }

        /** consent 服务 = 审计装饰（memory 实现）：consent.accepted 事件在 save 路径（装饰类注释）。 */
        @Bean
        @ConditionalOnMissingBean(OAuth2AuthorizationConsentService.class)
        AuditingOAuth2AuthorizationConsentService jauthAuthorizationConsentService(AuditEventPublisher auditPublisher) {
            return new AuditingOAuth2AuthorizationConsentService(
                    new InMemoryOAuth2AuthorizationConsentService(), auditPublisher);
        }

        /** /me 解析：授权令牌路径（PAT 在 memory 模式禁用，无叠加腿）。 */
        @Bean
        @ConditionalOnMissingBean(PlatformTokenResolver.class)
        AccessTokenPlatformTokenResolver jauthPlatformTokenResolver(OAuth2AuthorizationService authorizationService) {
            return new AccessTokenPlatformTokenResolver(
                    token -> authorizationService.findByToken(token, OAuth2TokenType.ACCESS_TOKEN));
        }

        @Bean
        @ConditionalOnMissingBean(UserRepository.class)
        InMemoryUserRepository jauthUserRepository() {
            return new InMemoryUserRepository();
        }

        /** passkey 凭据仓储（memory 版，仅 passkey 开启；宿主可整体替换，凭据生命周期审计在仓储内）。 */
        @Bean
        @ConditionalOnProperty(name = "jauth-hub.passkey.enabled", havingValue = "true")
        @ConditionalOnMissingBean(UserCredentialRepository.class)
        InMemoryPasskeyCredentialRepository jauthPasskeyCredentialRepository(AuditEventPublisher auditPublisher) {
            return new InMemoryPasskeyCredentialRepository(auditPublisher);
        }

        /**
         * owner 解析（B9）：memory 模式的进程内登记表——种子客户端不带归属，宿主/测试经 put 登记，
         * 未登记即平台语义（ceiling 不剪）。
         */
        @Bean
        @ConditionalOnMissingBean(ClientOwnerResolver.class)
        InMemoryClientOwnerResolver jauthClientOwnerResolver() {
            return new InMemoryClientOwnerResolver();
        }

        @Bean
        @ConditionalOnMissingBean(OrgRepository.class)
        InMemoryOrgRepository jauthOrgRepository() {
            return new InMemoryOrgRepository();
        }

        @Bean
        @ConditionalOnMissingBean(InstallationRepository.class)
        InMemoryInstallationRepository jauthInstallationRepository() {
            return new InMemoryInstallationRepository();
        }

        /**
         * 短命签名密钥源：进程内生成 RSA-2048 单钥，重启即换（memory 模式语义：密钥不落表、无轮转调度）。
         * 既有令牌重启后全部失验，属该模式面向 demo/轻嵌入的既定代价（SPEC §3 memory 语义行）。
         */
        @Bean
        @ConditionalOnMissingBean(JWKSource.class)
        JWKSource<SecurityContext> jauthJwkSource() {
            return ephemeralJwkSource();
        }

        private static JWKSource<SecurityContext> ephemeralJwkSource() {
            try {
                KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
                keyPairGenerator.initialize(2048);
                KeyPair keyPair = keyPairGenerator.generateKeyPair();
                RSAKey rsaJwk = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                        .keyID(UUID.randomUUID().toString())
                        .algorithm(JWSAlgorithm.RS256)
                        .keyUse(KeyUse.SIGNATURE)
                        .privateKey(keyPair.getPrivate())
                        .build();
                JWKSet jwkSet = new JWKSet(rsaJwk);
                return (jwkSelector, context) -> jwkSelector.select(jwkSet);
            } catch (NoSuchAlgorithmException ex) {
                // JDK 规范保证 RSA 可用（缺失即 JVM 不合规），此分支只是受检异常的显式翻译
                throw new IllegalStateException("RSA key generation unavailable on this JVM", ex);
            }
        }
    }

    /**
     * jdbc 存储（显式选择，宿主必须供 DataSource——嵌入契约决议 4，缺失即缺 bean 启动失败）：core JauthJdbc
     * 三件（client owner 列 / authorization 令牌哈希+RTR 熔断 / consent 框架原样）+ 族谱/用户/JWK 仓储。
     *
     * <p><b>Flyway 编程式实例</b>：历史表用 {@value #FLYWAY_HISTORY_TABLE}——宿主自己也跑 Flyway
     * （flyway_schema_history）也不与本侧迁移撞表；locations 指 core 命名空间目录。所有 JDBC 仓储 bean
     * 依赖 {@link FlywayMigrationGuard} 参数强制"先迁移后建仓储"（框架 Jdbc* 构造即读表元数据）。H2 使用
     * 建议连 {@code MODE=PostgreSQL}（双兼容 SQL 的测试基准，SPEC §1 决议 8）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "jauth-hub.storage", havingValue = JauthHubProperties.STORAGE_JDBC)
    static class JdbcStorageConfiguration {

        /** 迁移完成标记 bean：无状态，仅作 JDBC 仓储 bean 的构造前置依赖（见类注释）。 */
        @Bean
        FlywayMigrationGuard jauthFlywayMigration(DataSource dataSource) {
            Flyway.configure()
                    .dataSource(dataSource)
                    .table(FLYWAY_HISTORY_TABLE)
                    .locations("classpath:" + CORE_NAMESPACE + "/flyway")
                    .load()
                    .migrate();
            return new FlywayMigrationGuard();
        }

        @Bean
        @ConditionalOnMissingBean
        JauthJdbcRegisteredClientRepository jauthRegisteredClientRepository(
                DataSource dataSource, FlywayMigrationGuard migrationGuard) {
            return new JauthJdbcRegisteredClientRepository(new JdbcTemplate(dataSource));
        }

        @Bean
        @ConditionalOnMissingBean
        JdbcTokenFamilyService jauthTokenFamilyService(DataSource dataSource, FlywayMigrationGuard migrationGuard) {
            return new JdbcTokenFamilyService(new JdbcTemplate(dataSource));
        }

        /** jdbc 审计：jauth_audit_event 追加只写（V2 建表）。 */
        @Bean
        @ConditionalOnMissingBean(AuditEventPublisher.class)
        JdbcAuditEventService jauthAuditEventService(DataSource dataSource, Clock clock) {
            return new JdbcAuditEventService(new JdbcTemplate(dataSource), clock);
        }

        /**
         * 授权服务 = 审计装饰（PAT 叠加 → JDBC 哈希手术版）：读取路径 PAT 回退在叠加层（B5 闭环），
         * 生命周期审计在最外层（save/remove 单点）。ceiling 取交（B9）在最内层紧贴 base——外层审计/
         * PAT 必须观察到剪后状态。PAT 叠加构造幂等播种 jauth-pat 伪客户端（内省 client_id 反查所需，
         * 叠加层类注释）。
         */
        @Bean
        @ConditionalOnMissingBean(OAuth2AuthorizationService.class)
        AuditingOAuth2AuthorizationService jauthAuthorizationService(
                DataSource dataSource,
                JauthJdbcRegisteredClientRepository registeredClientRepository,
                JdbcTokenFamilyService tokenFamilyService,
                UserRepository userRepository,
                OrgScopeGate orgScopeGate,
                AuditEventPublisher auditPublisher,
                Clock clock,
                ObjectProvider<MeterRegistry> meterRegistry,
                FlywayMigrationGuard migrationGuard) {
            PatIntrospectionSupport patSupport = new PatIntrospectionSupport(
                    new JdbcTemplate(dataSource), registeredClientRepository, userRepository, clock);
            return new AuditingOAuth2AuthorizationService(
                    new PatAwareOAuth2AuthorizationService(
                            new CeilingAwareOAuth2AuthorizationService(
                                    new JauthJdbcOAuth2AuthorizationService(
                                            new JdbcTemplate(dataSource),
                                            registeredClientRepository,
                                            tokenFamilyService),
                                    new JdbcClientOwnerResolver(registeredClientRepository),
                                    orgScopeGate),
                            patSupport),
                    auditPublisher,
                    meterRegistry.getIfAvailable());
        }

        /** consent 服务 = 审计装饰（JDBC 实现）。 */
        @Bean
        @ConditionalOnMissingBean(OAuth2AuthorizationConsentService.class)
        AuditingOAuth2AuthorizationConsentService jauthAuthorizationConsentService(
                DataSource dataSource,
                JauthJdbcRegisteredClientRepository registeredClientRepository,
                AuditEventPublisher auditPublisher,
                FlywayMigrationGuard migrationGuard) {
            return new AuditingOAuth2AuthorizationConsentService(
                    new JdbcOAuth2AuthorizationConsentService(new JdbcTemplate(dataSource), registeredClientRepository),
                    auditPublisher);
        }

        /** /me 解析：授权令牌 + PAT 同一 findByToken 回退面（PAT 叠加层已在授权服务内）。 */
        @Bean
        @ConditionalOnMissingBean(PlatformTokenResolver.class)
        AccessTokenPlatformTokenResolver jauthPlatformTokenResolver(OAuth2AuthorizationService authorizationService) {
            return new AccessTokenPlatformTokenResolver(
                    token -> authorizationService.findByToken(token, OAuth2TokenType.ACCESS_TOKEN));
        }

        @Bean
        @ConditionalOnMissingBean(UserRepository.class)
        JdbcUserRepository jauthUserRepository(DataSource dataSource, FlywayMigrationGuard migrationGuard) {
            return new JdbcUserRepository(new JdbcTemplate(dataSource));
        }

        /** passkey 凭据仓储（jdbc 版，V8 迁移后的 15 列表；依赖迁移先行建仓储）。 */
        @Bean
        @ConditionalOnProperty(name = "jauth-hub.passkey.enabled", havingValue = "true")
        @ConditionalOnMissingBean(UserCredentialRepository.class)
        JdbcPasskeyCredentialRepository jauthPasskeyCredentialRepository(
                DataSource dataSource, AuditEventPublisher auditPublisher, FlywayMigrationGuard migrationGuard) {
            return new JdbcPasskeyCredentialRepository(new JdbcTemplate(dataSource), auditPublisher);
        }

        /** owner 解析（B9）：jdbc 模式读 owner 两列（JauthJdbcRegisteredClientRepository 既有路径）。 */
        @Bean
        @ConditionalOnMissingBean(ClientOwnerResolver.class)
        JdbcClientOwnerResolver jauthClientOwnerResolver(
                JauthJdbcRegisteredClientRepository registeredClientRepository) {
            return new JdbcClientOwnerResolver(registeredClientRepository);
        }

        @Bean
        @ConditionalOnMissingBean(OrgRepository.class)
        JdbcOrgRepository jauthOrgRepository(DataSource dataSource, FlywayMigrationGuard migrationGuard) {
            return new JdbcOrgRepository(new JdbcTemplate(dataSource));
        }

        @Bean
        @ConditionalOnMissingBean(InstallationRepository.class)
        JdbcInstallationRepository jauthInstallationRepository(
                DataSource dataSource, FlywayMigrationGuard migrationGuard) {
            return new JdbcInstallationRepository(new JdbcTemplate(dataSource));
        }

        @Bean
        @ConditionalOnMissingBean(Clock.class)
        Clock jauthClock() {
            return Clock.systemUTC();
        }

        @Bean
        @ConditionalOnMissingBean
        JdbcJwkRepository jauthJwkRepository(DataSource dataSource, FlywayMigrationGuard migrationGuard) {
            return new JdbcJwkRepository(new JdbcTemplate(dataSource));
        }

        @Bean
        @ConditionalOnMissingBean(JWKSource.class)
        JauthJwkSource jauthJwkSource(JdbcJwkRepository jwkRepository, FlywayMigrationGuard migrationGuard) {
            return new JauthJwkSource(jwkRepository);
        }

        /** 轮转调度（90d 轮转 + 14d 重叠，§6）：DB 化供多实例共享，单测/装配测试直调。 */
        @Bean
        @ConditionalOnMissingBean
        JwkRotationService jauthJwkRotationService(
                JdbcJwkRepository jwkRepository, Clock clock, FlywayMigrationGuard migrationGuard) {
            return new JwkRotationService(jwkRepository, clock);
        }

        /** JWK 轮转生命周期：挂 SmartLifecycle，随 context 启停（core JwkRotationService 生命周期注释）。 */
        @Bean
        JwkRotationLifecycle jauthJwkRotationLifecycle(JwkRotationService jwkRotationService) {
            return new JwkRotationLifecycle(jwkRotationService);
        }

        /**
         * 播种事务模板：宿主有事务管理器即有（Boot 对 DataSource 自动装配），供播种 runner 包事务收口
         * owner 两列非原子问题（见 runner 注释）；无事务管理器时缺省，播种退化直跑（单语句 INSERT 原子）。
         */
        @Bean
        @ConditionalOnMissingBean(TransactionTemplate.class)
        @ConditionalOnBean(PlatformTransactionManager.class)
        TransactionTemplate jauthSeedingTransactionTemplate(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }
    }

    /** 无状态迁移完成标记（见 JdbcStorageConfiguration 类注释）。 */
    static final class FlywayMigrationGuard {}

    /**
     * JWK 轮转的 SmartLifecycle 挂钩：context 启动即 start（initialDelay=0 补欠账轮转），关闭即 stop
     * （不等待在途任务）。isRunning 供装配测试观察启停。
     */
    static final class JwkRotationLifecycle implements SmartLifecycle {

        private final JwkRotationService rotationService;

        private volatile boolean running;

        JwkRotationLifecycle(JwkRotationService rotationService) {
            this.rotationService = rotationService;
        }

        @Override
        public void start() {
            this.rotationService.start();
            this.running = true;
        }

        @Override
        public void stop() {
            this.rotationService.stop();
            this.running = false;
        }

        @Override
        public boolean isRunning() {
            return this.running;
        }
    }
}
