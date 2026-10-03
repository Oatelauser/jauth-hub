package io.github.oatelauser.jauth.selfservice;

import io.github.oatelauser.jauth.core.audit.AuditEventPublisher;
import io.github.oatelauser.jauth.core.client.InMemoryClientOwnerResolver;
import io.github.oatelauser.jauth.core.client.JauthJdbcRegisteredClientRepository;
import io.github.oatelauser.jauth.core.org.InstallationRepository;
import io.github.oatelauser.jauth.core.org.InstallationService;
import io.github.oatelauser.jauth.core.org.OrgRepository;
import io.github.oatelauser.jauth.core.org.OrgService;
import io.github.oatelauser.jauth.core.ratelimit.RateLimiter;
import io.github.oatelauser.jauth.core.response.ResponseRenderer;
import io.github.oatelauser.jauth.core.scope.ScopeCatalog;
import io.github.oatelauser.jauth.core.token.InMemoryTokenFamilyService;
import io.github.oatelauser.jauth.core.token.JdbcTokenFamilyService;
import io.github.oatelauser.jauth.core.user.SudoGate;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import io.github.oatelauser.jauth.core.web.PasskeyFlag;
import io.github.oatelauser.jauth.selfservice.pat.JdbcPatService;
import io.github.oatelauser.jauth.selfservice.pat.PatService;
import io.github.oatelauser.jauth.selfservice.web.AppsStateController;
import io.github.oatelauser.jauth.selfservice.web.AuthorizedAppService;
import io.github.oatelauser.jauth.selfservice.web.AuthorizedAppsController;
import io.github.oatelauser.jauth.selfservice.web.InMemoryOwnedAppService;
import io.github.oatelauser.jauth.selfservice.web.JdbcOwnedAppService;
import io.github.oatelauser.jauth.selfservice.web.MyAppsController;
import io.github.oatelauser.jauth.selfservice.web.MyAppsStateController;
import io.github.oatelauser.jauth.selfservice.web.MyOrgsController;
import io.github.oatelauser.jauth.selfservice.web.MyOrgsStateController;
import io.github.oatelauser.jauth.selfservice.web.OrgAppsController;
import io.github.oatelauser.jauth.selfservice.web.OrgAppsStateController;
import io.github.oatelauser.jauth.selfservice.web.OrgInstallationsController;
import io.github.oatelauser.jauth.selfservice.web.OrgInstallationsStateController;
import io.github.oatelauser.jauth.selfservice.web.OrgMembersController;
import io.github.oatelauser.jauth.selfservice.web.OrgMembersStateController;
import io.github.oatelauser.jauth.selfservice.web.OwnedAppService;
import io.github.oatelauser.jauth.selfservice.web.PasskeyController;
import io.github.oatelauser.jauth.selfservice.web.PasskeyStateController;
import io.github.oatelauser.jauth.selfservice.web.PatController;
import io.github.oatelauser.jauth.selfservice.web.PatStateController;
import io.github.oatelauser.jauth.selfservice.web.SensitiveScopeSudoInterceptor;
import io.github.oatelauser.jauth.selfservice.web.SudoController;
import io.github.oatelauser.jauth.selfservice.web.SudoInterceptor;
import io.github.oatelauser.jauth.selfservice.web.SudoStateController;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * selfservice 自带装配（SPEC §2：模块独立于 starter 提供页面，app 依赖、宿主可选依赖）。
 *
 * <p><b>与 starter 的关系</b>：本配置排在其后求值（afterName）——页面控制器消费的领域 bean
 * （RegisteredClientRepository / ScopeCatalog / UserRepository / EducationalFlag / ResponseRenderer）与 jdbc
 * 模式的 Clock 均由 starter 装配；以 {@link ConditionalOnBean}(RegisteredClientRepository) 作"starter 在场"
 * 探测，宿主未引 starter 时本模块整体静默让位（页面 404），不制造半残装配。不改动 starter 任何既有装配。
 *
 * <p><b>存储门控</b>（04 票）：PAT 与看板查询服务只在 {@code jauth-hub.storage=jdbc} 注册；memory 模式页面
 * 控制器仍在（渲染"当前存储模式不支持"提示，不 500）。<b>我的应用（B10）例外</b>：memory 模式可用（框架内存
 * 仓库 + owner 登记表，MemorySelfServiceConfiguration），只有 PAT/看板维持 jdbc-only 门控。
 *
 * <p><b>视图退场</b>（v1.5 B5b）：SSR 模板与视图解析器链已拆除，页面路由一律 302 到 {@code /front/<路由>}
 * 的 SPA 皮；本模块 i18n bundle 的页面 chrome 键随之成为死键（文件保留，修剪挂 v1.6 滑账）。
 *
 * @author oatelauser
 */
@AutoConfiguration(afterName = "io.github.oatelauser.jauth.starter.JauthHubAutoConfiguration")
public class JauthSelfServiceAutoConfiguration {

    /** 页面控制器与 JSON 状态面（两存储模式都注册：memory 模式状态体给 supported=false 提示态）。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(RegisteredClientRepository.class)
    static class PageConfiguration {

        @Bean
        @ConditionalOnMissingBean
        PatController jauthPatController(
                ObjectProvider<PatService> patService,
                ScopeCatalog scopeCatalog,
                UserRepository userRepository,
                ResponseRenderer responseRenderer,
                ObjectProvider<Clock> clock,
                RateLimiter rateLimiter) {
            return new PatController(
                    patService.getIfAvailable(),
                    scopeCatalog,
                    userRepository,
                    responseRenderer,
                    clock.getIfAvailable(Clock::systemUTC),
                    rateLimiter);
        }

        @Bean
        @ConditionalOnMissingBean
        AuthorizedAppsController jauthAuthorizedAppsController(
                ObjectProvider<AuthorizedAppService> appService,
                ObjectProvider<OAuth2AuthorizationService> authorizationService,
                ObjectProvider<OAuth2AuthorizationConsentService> consentService,
                RegisteredClientRepository clientRepository,
                ResponseRenderer responseRenderer) {
            return new AuthorizedAppsController(
                    appService.getIfAvailable(),
                    authorizationService,
                    consentService,
                    clientRepository,
                    responseRenderer);
        }

        /**
         * 通行密钥页路由（v1.2 C2；v1.5 B5b 起纯 302）：凭据仓储的门控语义移到 JSON 状态面
         * （passkeyEnabled=false 状态体，页面不 500）。
         */
        @Bean
        @ConditionalOnMissingBean
        PasskeyController jauthPasskeyController() {
            return new PasskeyController();
        }

        /**
         * 通行密钥页 JSON 状态面（v1.5 B1c）：门控与依赖形态照 jauthPasskeyController——凭据仓储经
         * ObjectProvider 持有，缺席（passkey 关）即 passkeyEnabled=false 状态体（不 500）。
         */
        @Bean
        @ConditionalOnMissingBean
        PasskeyStateController jauthPasskeyStateController(
                ObjectProvider<UserCredentialRepository> credentials,
                UserRepository userRepository,
                EducationalFlag educational,
                ResponseRenderer responseRenderer) {
            return new PasskeyStateController(credentials, userRepository, educational, responseRenderer);
        }

        /** sudo 验证页路由（v1.2 C3；v1.5 B5b 起纯 302 到 /front/sudo，无渲染依赖）。 */
        @Bean
        @ConditionalOnMissingBean
        SudoController jauthSudoController() {
            return new SudoController();
        }

        /**
         * sudo 页 JSON 状态面（v1.4 B1）：门控与依赖形态照 jauthSudoController——SudoGate 缺席（sudo 关）
         * 即 sudoEnabled=false 的状态体（页面皮渲染"未启用"，headless 皮照 data 分支）；认证归部署方
         * default 链（同 SSR 页，starter 协议链不认领 /api/sudo）。
         */
        @Bean
        @ConditionalOnMissingBean
        SudoStateController jauthSudoStateController(
                ObjectProvider<SudoGate> sudoGate,
                ObjectProvider<PasskeyFlag> passkey,
                EducationalFlag educational,
                ResponseRenderer responseRenderer) {
            return new SudoStateController(
                    sudoGate, passkey.getIfAvailable(() -> PasskeyFlag.OFF), educational, responseRenderer);
        }

        /**
         * 看板页 JSON 状态面（v1.5 B1a）：依赖与降级形态照 jauthAuthorizedAppsController——服务缺席
         * （memory 模式）即 appsSupported=false 状态体；PasskeyFlag 宿主缺它时降级 OFF 同先例。
         */
        @Bean
        @ConditionalOnMissingBean
        AppsStateController jauthAppsStateController(
                ObjectProvider<AuthorizedAppService> appService,
                RegisteredClientRepository clientRepository,
                EducationalFlag educational,
                ObjectProvider<PasskeyFlag> passkey,
                ResponseRenderer responseRenderer) {
            return new AppsStateController(
                    appService.getIfAvailable(),
                    clientRepository,
                    educational,
                    passkey.getIfAvailable(() -> PasskeyFlag.OFF),
                    responseRenderer);
        }

        /** PAT 页 JSON 状态面（v1.5 B1a）：服务缺席（memory 模式）即 patSupported=false 状态体，Clock 缺席回退 UTC。 */
        @Bean
        @ConditionalOnMissingBean
        PatStateController jauthPatStateController(
                ObjectProvider<PatService> patService,
                ScopeCatalog scopeCatalog,
                UserRepository userRepository,
                MessageSource messageSource,
                EducationalFlag educational,
                ResponseRenderer responseRenderer,
                ObjectProvider<Clock> clock) {
            return new PatStateController(
                    patService.getIfAvailable(),
                    scopeCatalog,
                    userRepository,
                    messageSource,
                    educational,
                    responseRenderer,
                    clock.getIfAvailable(Clock::systemUTC));
        }

        /** 我的应用页 JSON 状态面（v1.5 B1a，my-app-new 页复用）：服务缺席即 appsSupported=false 状态体。 */
        @Bean
        @ConditionalOnMissingBean
        MyAppsStateController jauthMyAppsStateController(
                ObjectProvider<OwnedAppService> ownedAppService,
                UserRepository userRepository,
                EducationalFlag educational,
                ResponseRenderer responseRenderer) {
            return new MyAppsStateController(
                    ownedAppService.getIfAvailable(), userRepository, educational, responseRenderer);
        }

        /** 我的组织页 JSON 状态面（v1.5 B1a）：OrgService 缺席即 orgsSupported=false 状态体。 */
        @Bean
        @ConditionalOnMissingBean
        MyOrgsStateController jauthMyOrgsStateController(
                ObjectProvider<OrgService> orgService,
                UserRepository userRepository,
                EducationalFlag educational,
                ResponseRenderer responseRenderer) {
            return new MyOrgsStateController(
                    orgService.getIfAvailable(), userRepository, educational, responseRenderer);
        }

        /** org 应用页 JSON 状态面（v1.5 B1b）：依赖与 OWNER 门形态照 jauthOrgAppsController。 */
        @Bean
        @ConditionalOnMissingBean
        OrgAppsStateController jauthOrgAppsStateController(
                ObjectProvider<OwnedAppService> ownedAppService,
                ObjectProvider<OrgService> orgService,
                ObjectProvider<OrgRepository> orgRepository,
                UserRepository userRepository,
                EducationalFlag educational,
                ResponseRenderer responseRenderer) {
            return new OrgAppsStateController(
                    ownedAppService.getIfAvailable(),
                    orgService.getIfAvailable(),
                    orgRepository.getIfAvailable(),
                    userRepository,
                    educational,
                    responseRenderer);
        }

        /** 安装审批页 JSON 状态面（v1.5 B1b）：行装配/scope 目录复用 SSR 控制器的包内 statics。 */
        @Bean
        @ConditionalOnMissingBean
        OrgInstallationsStateController jauthOrgInstallationsStateController(
                ObjectProvider<OrgService> orgService,
                ObjectProvider<InstallationService> installationService,
                ObjectProvider<InstallationRepository> installationRepository,
                ObjectProvider<OrgRepository> orgRepository,
                UserRepository userRepository,
                RegisteredClientRepository clientRepository,
                ScopeCatalog scopeCatalog,
                MessageSource messageSource,
                EducationalFlag educational,
                ResponseRenderer responseRenderer) {
            return new OrgInstallationsStateController(
                    orgService.getIfAvailable(),
                    installationService.getIfAvailable(),
                    installationRepository.getIfAvailable(),
                    orgRepository.getIfAvailable(),
                    userRepository,
                    clientRepository,
                    scopeCatalog,
                    messageSource,
                    educational,
                    responseRenderer);
        }

        /** org 成员管理页 JSON 状态面（v1.5 B1b）：org 投影需 OrgRepository（supported 含其在场性）。 */
        @Bean
        @ConditionalOnMissingBean
        OrgMembersStateController jauthOrgMembersStateController(
                ObjectProvider<OrgService> orgService,
                ObjectProvider<OrgRepository> orgRepository,
                UserRepository userRepository,
                EducationalFlag educational,
                ResponseRenderer responseRenderer) {
            return new OrgMembersStateController(
                    orgService.getIfAvailable(),
                    orgRepository.getIfAvailable(),
                    userRepository,
                    educational,
                    responseRenderer);
        }

        /**
         * sudo 拦截门（v1.2 C3）：仅 SudoGate bean 在场（= {@code jauth-hub.sudo.enabled=true}，starter
         * 装配并已校验 passkey 依赖）时注册——关 = 零拦截零开销；WebMvcConfigurer 形态照 starter 的
         * jauthStaticResourcesConfigurer 先例。
         */
        @Bean
        @ConditionalOnBean(SudoGate.class)
        WebMvcConfigurer jauthSudoInterceptorConfigurer(
                SudoGate sudoGate, AuditEventPublisher auditPublisher, UserRepository userRepository) {
            return new WebMvcConfigurer() {
                @Override
                public void addInterceptors(InterceptorRegistry registry) {
                    registry.addInterceptor(new SudoInterceptor(sudoGate, auditPublisher, userRepository));
                    // 敏感 scope 联动门(v1.3 D2):与 SudoInterceptor 同场注册(sudo 开),零敏感注解时零行为
                    registry.addInterceptor(
                            new SensitiveScopeSudoInterceptor(sudoGate, auditPublisher, userRepository));
                }
            };
        }

        /** 我的应用面（B10）：memory 模式也可用（与 PAT/看板不同），服务 bean 由两段存储配置按模式供给。 */
        @Bean
        @ConditionalOnMissingBean
        MyAppsController jauthMyAppsController(
                ObjectProvider<OwnedAppService> ownedAppService,
                UserRepository userRepository,
                ResponseRenderer responseRenderer,
                AuditEventPublisher auditPublisher,
                RateLimiter rateLimiter) {
            return new MyAppsController(
                    ownedAppService.getIfAvailable(), userRepository, responseRenderer, auditPublisher, rateLimiter);
        }

        /**
         * 我的组织/安装审批/org 应用面（B11）：领域 bean（OrgService/InstallationService/两仓储）由 starter
         * 无条件供给，经 ObjectProvider 可缺省——宿主未引 starter 时整组已让位，个别 bean 缺席的装配边角
         * JSON 回 A0504（SPA 按状态面 supported=false 渲染提示态）。
         */
        @Bean
        @ConditionalOnMissingBean
        MyOrgsController jauthMyOrgsController(
                ObjectProvider<OrgService> orgService,
                UserRepository userRepository,
                ResponseRenderer responseRenderer) {
            return new MyOrgsController(orgService.getIfAvailable(), userRepository, responseRenderer);
        }

        /** 安装审批面（B11）：行装配共径供 JSON 状态面复用。 */
        @Bean
        @ConditionalOnMissingBean
        OrgInstallationsController jauthOrgInstallationsController(
                ObjectProvider<OrgService> orgService,
                ObjectProvider<InstallationService> installationService,
                ObjectProvider<InstallationRepository> installationRepository,
                ObjectProvider<OrgRepository> orgRepository,
                UserRepository userRepository,
                RegisteredClientRepository clientRepository,
                ScopeCatalog scopeCatalog,
                ResponseRenderer responseRenderer) {
            return new OrgInstallationsController(
                    orgService.getIfAvailable(),
                    installationService.getIfAvailable(),
                    installationRepository.getIfAvailable(),
                    orgRepository.getIfAvailable(),
                    userRepository,
                    clientRepository,
                    scopeCatalog,
                    responseRenderer);
        }

        /** org 成员管理面（v1.3 D3，OWNER 面）：行装配共径供 JSON 状态面复用。 */
        @Bean
        @ConditionalOnMissingBean
        OrgMembersController jauthOrgMembersController(
                ObjectProvider<OrgService> orgService,
                UserRepository userRepository,
                ResponseRenderer responseRenderer) {
            return new OrgMembersController(orgService.getIfAvailable(), userRepository, responseRenderer);
        }

        /** org 应用面（B11）：注册/编辑 JSON 面，OWNER 门在控制器入口。 */
        @Bean
        @ConditionalOnMissingBean
        OrgAppsController jauthOrgAppsController(
                ObjectProvider<OwnedAppService> ownedAppService,
                ObjectProvider<OrgService> orgService,
                UserRepository userRepository,
                ResponseRenderer responseRenderer,
                AuditEventPublisher auditPublisher,
                RateLimiter rateLimiter) {
            return new OrgAppsController(
                    ownedAppService.getIfAvailable(),
                    orgService.getIfAvailable(),
                    userRepository,
                    responseRenderer,
                    auditPublisher,
                    rateLimiter);
        }
    }

    /**
     * jdbc 模式的自助数据服务：PAT 存储 + 授权看板查询 + 我的应用（client+owner 两写包事务，B10 滑账①收口）。
     * jauth_pat / oauth2_authorization 表由 starter 的 Flyway 迁移建立（context 刷新完成先于首个请求），本组
     * bean 构造不触库、无需重复迁移守卫。事务模板取法照 seeder：ObjectProvider 可缺省（容器无事务管理器时
     * 退化为两条语句直跑，JdbcOwnedAppService 类注释），starter 的 jauthSeedingTransactionTemplate 或宿主
     * 自带模板均可让位复用。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "jauth-hub.storage", havingValue = "jdbc")
    static class JdbcSelfServiceConfiguration {

        @Bean
        @ConditionalOnMissingBean(PatService.class)
        JdbcPatService jauthPatService(DataSource dataSource, Clock clock) {
            return new JdbcPatService(new JdbcTemplate(dataSource), clock);
        }

        @Bean
        @ConditionalOnMissingBean(AuthorizedAppService.class)
        AuthorizedAppService jauthAuthorizedAppService(DataSource dataSource) {
            return new AuthorizedAppService(new JdbcTemplate(dataSource));
        }

        /** 以 JauthJdbc 仓储在场为前提（宿主整体换掉 client 仓储时应用管理退为页面提示态，不半残装配）。 */
        @Bean
        @ConditionalOnMissingBean(OwnedAppService.class)
        @ConditionalOnBean(JauthJdbcRegisteredClientRepository.class)
        JdbcOwnedAppService jauthOwnedAppService(
                JauthJdbcRegisteredClientRepository clientRepository,
                DataSource dataSource,
                ObjectProvider<TransactionTemplate> transactionTemplate,
                JdbcTokenFamilyService tokenFamilyService,
                PasswordEncoder passwordEncoder,
                ScopeCatalog scopeCatalog,
                Clock clock) {
            return new JdbcOwnedAppService(
                    clientRepository,
                    new JdbcTemplate(dataSource),
                    transactionTemplate.getIfAvailable(),
                    tokenFamilyService,
                    passwordEncoder,
                    scopeCatalog,
                    clock);
        }
    }

    /**
     * memory 模式的我的应用服务（B10：应用管理与 PAT 不同，memory 可用）：client 落框架内存仓库、owner 走
     * InMemoryClientOwnerResolver 登记表，两者皆 starter memory 装配供给（缺任一即本组静默让位，页面提示态）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "jauth-hub.storage", havingValue = "memory", matchIfMissing = true)
    static class MemorySelfServiceConfiguration {

        @Bean
        @ConditionalOnMissingBean(OwnedAppService.class)
        @ConditionalOnBean({RegisteredClientRepository.class, InMemoryClientOwnerResolver.class})
        InMemoryOwnedAppService jauthOwnedAppService(
                RegisteredClientRepository clientRepository,
                InMemoryClientOwnerResolver ownerResolver,
                InMemoryTokenFamilyService tokenFamilyService,
                PasswordEncoder passwordEncoder,
                ScopeCatalog scopeCatalog,
                ObjectProvider<Clock> clock) {
            return new InMemoryOwnedAppService(
                    clientRepository,
                    ownerResolver,
                    tokenFamilyService,
                    passwordEncoder,
                    scopeCatalog,
                    clock.getIfAvailable(Clock::systemUTC));
        }
    }
}
