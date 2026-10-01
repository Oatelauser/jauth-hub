package io.github.oatelauser.jauth.selfservice;

import io.github.oatelauser.jauth.core.audit.AuditEventPublisher;
import io.github.oatelauser.jauth.core.client.InMemoryClientOwnerResolver;
import io.github.oatelauser.jauth.core.client.JauthJdbcRegisteredClientRepository;
import io.github.oatelauser.jauth.core.org.InstallationRepository;
import io.github.oatelauser.jauth.core.org.InstallationService;
import io.github.oatelauser.jauth.core.org.OrgRepository;
import io.github.oatelauser.jauth.core.org.OrgService;
import io.github.oatelauser.jauth.core.response.ResponseRenderer;
import io.github.oatelauser.jauth.core.scope.ScopeCatalog;
import io.github.oatelauser.jauth.core.user.SudoGate;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import io.github.oatelauser.jauth.core.web.PasskeyFlag;
import io.github.oatelauser.jauth.selfservice.pat.JdbcPatService;
import io.github.oatelauser.jauth.selfservice.pat.PatService;
import io.github.oatelauser.jauth.selfservice.web.AuthorizedAppService;
import io.github.oatelauser.jauth.selfservice.web.AuthorizedAppsController;
import io.github.oatelauser.jauth.selfservice.web.InMemoryOwnedAppService;
import io.github.oatelauser.jauth.selfservice.web.JdbcOwnedAppService;
import io.github.oatelauser.jauth.selfservice.web.MyAppsController;
import io.github.oatelauser.jauth.selfservice.web.MyOrgsController;
import io.github.oatelauser.jauth.selfservice.web.OrgAppsController;
import io.github.oatelauser.jauth.selfservice.web.OrgInstallationsController;
import io.github.oatelauser.jauth.selfservice.web.OwnedAppService;
import io.github.oatelauser.jauth.selfservice.web.PasskeyController;
import io.github.oatelauser.jauth.selfservice.web.PatController;
import io.github.oatelauser.jauth.selfservice.web.SudoController;
import io.github.oatelauser.jauth.selfservice.web.SudoInterceptor;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.ViewResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templatemode.TemplateMode;

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
 * <p><b>视图与 i18n 命名空间</b>：selfservice 自持一套模板解析器（前缀指向本模块命名空间，checkExistence +
 * 高序位，未命中穿透宿主默认解析器）与消息链（本模块 basename 优先，parent 挂上下文 messageSource——core 的
 * jauth.* 键与宿主覆盖均按 starter 复合源的同一解析顺序生效）。模板引用 core 的 fragments/layout（教学块/视觉
 * 复用，B3），故解析器链尾再挂一个 core 命名空间的只读解析器。
 *
 * @author oatelauser
 */
@AutoConfiguration(afterName = "io.github.oatelauser.jauth.starter.JauthHubAutoConfiguration")
public class JauthSelfServiceAutoConfiguration {

    /** selfservice 资源命名空间根（模板/i18n 与 core 同款防撞宿主方案）。 */
    static final String SELF_SERVICE_NAMESPACE = "io/github/oatelauser/jauth/selfservice";

    static final String SELF_SERVICE_TEMPLATES_PREFIX = "classpath:" + SELF_SERVICE_NAMESPACE + "/web/templates/";

    static final String SELF_SERVICE_I18N_BASENAME = SELF_SERVICE_NAMESPACE + "/i18n/messages";

    /**
     * core 命名空间串接（模板 fragments/layout 与 core 文案）：与 starter 的 CORE_* 常量取值相同，但不可跨模块引
     * 用（本批模块边界只开放 core 的 ClientSeeder），此处按"core 命名空间是 SPEC 锁定的公开资源路径"复制。
     */
    static final String CORE_TEMPLATES_PREFIX = "classpath:io/github/oatelauser/jauth/core/web/templates/";

    /** 页面控制器与视图基建（两存储模式都注册：memory 模式渲染不支持提示）。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(RegisteredClientRepository.class)
    static class PageConfiguration {

        @Bean
        @ConditionalOnMissingBean
        PatController jauthPatController(
                ObjectProvider<PatService> patService,
                ScopeCatalog scopeCatalog,
                UserRepository userRepository,
                MessageSource messageSource,
                EducationalFlag educational,
                ResponseRenderer responseRenderer,
                ObjectProvider<Clock> clock) {
            return new PatController(
                    patService.getIfAvailable(),
                    scopeCatalog,
                    userRepository,
                    messageSource,
                    educational,
                    responseRenderer,
                    clock.getIfAvailable(Clock::systemUTC));
        }

        @Bean
        @ConditionalOnMissingBean
        AuthorizedAppsController jauthAuthorizedAppsController(
                ObjectProvider<AuthorizedAppService> appService,
                ObjectProvider<OAuth2AuthorizationService> authorizationService,
                ObjectProvider<OAuth2AuthorizationConsentService> consentService,
                RegisteredClientRepository clientRepository,
                EducationalFlag educational,
                ObjectProvider<PasskeyFlag> passkey,
                ResponseRenderer responseRenderer) {
            // PasskeyFlag 由 starter 供给；宿主自带 bean 集而缺它时降级 OFF（看板不渲染 passkey 入口），
            // 与本配置"个别 bean 缺席渲染提示态"的边角容忍一致
            return new AuthorizedAppsController(
                    appService.getIfAvailable(),
                    authorizationService,
                    consentService,
                    clientRepository,
                    educational,
                    passkey.getIfAvailable(() -> PasskeyFlag.OFF),
                    responseRenderer);
        }

        /**
         * 通行密钥管理页（v1.2 C2）：凭据仓储 bean 仅 passkey 开启时由 starter 装配，缺席渲染"未启用"提示
         * （与 PAT 的 memory 门控同构，页面不 500）。
         */
        @Bean
        @ConditionalOnMissingBean
        PasskeyController jauthPasskeyController(
                ObjectProvider<UserCredentialRepository> credentials,
                UserRepository userRepository,
                EducationalFlag educational) {
            return new PasskeyController(credentials, userRepository, educational);
        }

        /**
         * sudo 验证页（v1.2 C3）：SudoGate 经 ObjectProvider 持有——缺席（sudo 关）即页面渲染"未启用"
         * 提示态（照 PasskeyController 门控形态）；PasskeyFlag 宿主缺它时降级 OFF 同看板先例。
         */
        @Bean
        @ConditionalOnMissingBean
        SudoController jauthSudoController(
                ObjectProvider<SudoGate> sudoGate, ObjectProvider<PasskeyFlag> passkey, EducationalFlag educational) {
            return new SudoController(sudoGate, passkey.getIfAvailable(() -> PasskeyFlag.OFF), educational);
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
                }
            };
        }

        /** 我的应用页（B10）：memory 模式也可用（与 PAT/看板不同），服务 bean 由两段存储配置按模式供给。 */
        @Bean
        @ConditionalOnMissingBean
        MyAppsController jauthMyAppsController(
                ObjectProvider<OwnedAppService> ownedAppService,
                UserRepository userRepository,
                EducationalFlag educational,
                ResponseRenderer responseRenderer) {
            return new MyAppsController(
                    ownedAppService.getIfAvailable(), userRepository, educational, responseRenderer);
        }

        /**
         * 我的组织/安装审批/org 应用页（B11）：领域 bean（OrgService/InstallationService/两仓储）由 starter
         * 无条件供给，经 ObjectProvider 可缺省——宿主未引 starter 时整组已让位，个别 bean 缺席的装配边角渲染
         * "不支持"提示态（页面不 500，JSON 回 A0504）。
         */
        @Bean
        @ConditionalOnMissingBean
        MyOrgsController jauthMyOrgsController(
                ObjectProvider<OrgService> orgService,
                UserRepository userRepository,
                EducationalFlag educational,
                ResponseRenderer responseRenderer) {
            return new MyOrgsController(orgService.getIfAvailable(), userRepository, educational, responseRenderer);
        }

        /** 安装审批页（B11）：OWNER 面，scope 目录项经 MessageSource 出 i18n 描述（照 PatController）。 */
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
                MessageSource messageSource,
                EducationalFlag educational,
                ResponseRenderer responseRenderer) {
            return new OrgInstallationsController(
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

        /** org 应用页（B11）：注册/列表面，OWNER 门在控制器入口。 */
        @Bean
        @ConditionalOnMissingBean
        OrgAppsController jauthOrgAppsController(
                ObjectProvider<OwnedAppService> ownedAppService,
                ObjectProvider<OrgService> orgService,
                ObjectProvider<OrgRepository> orgRepository,
                UserRepository userRepository,
                EducationalFlag educational,
                ResponseRenderer responseRenderer) {
            return new OrgAppsController(
                    ownedAppService.getIfAvailable(),
                    orgService.getIfAvailable(),
                    orgRepository.getIfAvailable(),
                    userRepository,
                    educational,
                    responseRenderer);
        }

        /**
         * selfservice 视图解析器：自带引擎 + 双解析器链（本模块命名空间优先，core 命名空间兜底供 fragments/layout
         * 解析），消息源自持 basename 并挂 parent。viewNames 白名单钉死只认领本模块九个视图名
         * （thymeleaf-spring6 的 ThymeleafViewResolver 无 checkExistence），其余视图穿透宿主/Boot 默认解析器；
         * core 三页仍走共享引擎，两套视图名不相交。
         */
        @Bean
        @ConditionalOnMissingBean(name = "jauthSelfServiceViewResolver")
        ViewResolver jauthSelfServiceViewResolver(
                ObjectProvider<MessageSource> messageSource, ApplicationContext applicationContext) {
            SpringTemplateEngine engine = new SpringTemplateEngine();
            engine.setTemplateResolver(
                    templateResolver(SELF_SERVICE_TEMPLATES_PREFIX, Ordered.HIGHEST_PRECEDENCE, applicationContext));
            engine.addTemplateResolver(
                    templateResolver(CORE_TEMPLATES_PREFIX, Ordered.HIGHEST_PRECEDENCE + 1, applicationContext));
            ResourceBundleMessageSource localMessages = new ResourceBundleMessageSource();
            localMessages.setBasename(SELF_SERVICE_I18N_BASENAME);
            localMessages.setDefaultEncoding(StandardCharsets.UTF_8.name());
            // parent = 上下文 messageSource（starter 复合源：core basename + 宿主 spring.messages.*）——
            // core 的 jauth.* 键与宿主覆盖在自助页与协议页保持同一解析语义
            localMessages.setParentMessageSource(messageSource.getIfAvailable());
            engine.setMessageSource(localMessages);

            ThymeleafViewResolver viewResolver = new ThymeleafViewResolver();
            viewResolver.setTemplateEngine(engine);
            viewResolver.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
            viewResolver.setViewNames(new String[] {
                PatController.VIEW_PAT,
                AuthorizedAppsController.VIEW_APPS,
                PasskeyController.VIEW_PASSKEY,
                SudoController.VIEW_SUDO,
                MyAppsController.VIEW_MY_APPS,
                MyAppsController.VIEW_MY_APP_NEW,
                MyOrgsController.VIEW_MY_ORGS,
                OrgInstallationsController.VIEW_ORG_INSTALLATIONS,
                OrgAppsController.VIEW_ORG_APPS
            });
            viewResolver.setContentType("text/html;charset=UTF-8");
            viewResolver.setForceContentType(true);
            return viewResolver;
        }

        /**
         * 解析器在 @Bean 方法体内构造（非容器 bean，无 Aware 注入），而 Thymeleaf 3.1 起引擎不再向解析器
         * 传导 ApplicationContext——必须显式传入，否则真渲染期 "Application Context cannot be null"
         * （v1.2.1 修复：v1.0 起潜伏，standalone 页面测试不真渲染模板故从未暴露）。
         */
        private static SpringResourceTemplateResolver templateResolver(
                String prefix, int order, ApplicationContext applicationContext) {
            SpringResourceTemplateResolver resolver = new SpringResourceTemplateResolver();
            resolver.setApplicationContext(applicationContext);
            resolver.setPrefix(prefix);
            resolver.setSuffix(".html");
            resolver.setTemplateMode(TemplateMode.HTML);
            resolver.setCheckExistence(true);
            resolver.setOrder(order);
            return resolver;
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
                PasswordEncoder passwordEncoder,
                ScopeCatalog scopeCatalog,
                Clock clock) {
            return new JdbcOwnedAppService(
                    clientRepository,
                    new JdbcTemplate(dataSource),
                    transactionTemplate.getIfAvailable(),
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
                PasswordEncoder passwordEncoder,
                ScopeCatalog scopeCatalog,
                ObjectProvider<Clock> clock) {
            return new InMemoryOwnedAppService(
                    clientRepository,
                    ownerResolver,
                    passwordEncoder,
                    scopeCatalog,
                    clock.getIfAvailable(Clock::systemUTC));
        }
    }
}
