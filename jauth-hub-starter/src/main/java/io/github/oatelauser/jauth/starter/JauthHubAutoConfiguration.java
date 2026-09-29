package io.github.oatelauser.jauth.starter;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.github.oatelauser.jauth.core.authorization.JauthJdbcOAuth2AuthorizationService;
import io.github.oatelauser.jauth.core.client.ClientSeedProperties;
import io.github.oatelauser.jauth.core.client.ClientSeeder;
import io.github.oatelauser.jauth.core.client.JauthJdbcRegisteredClientRepository;
import io.github.oatelauser.jauth.core.response.DefaultResponseRenderer;
import io.github.oatelauser.jauth.core.response.JauthResponseAdvice;
import io.github.oatelauser.jauth.core.response.ResponseRenderer;
import io.github.oatelauser.jauth.core.scope.InMemoryScopeCatalog;
import io.github.oatelauser.jauth.core.scope.ScopeCatalog;
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
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.web.ConsentController;
import io.github.oatelauser.jauth.core.web.DeviceVerifyController;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import io.github.oatelauser.jauth.core.web.LoginController;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.accept.ContentNegotiationStrategy;
import org.springframework.web.accept.HeaderContentNegotiationStrategy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
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

    /** 协议链上自认领的页面路径（登录页 = formLogin 落点）。 */
    static final String LOGIN_PATH = "/login";

    /** consent 页（框架 authorizationEndpoint.consentPage 落点，B3 ConsentController）。 */
    static final String CONSENT_PAGE_PATH = "/oauth2/consent";

    /** 设备验证页：AuthorizationServerSettings.deviceVerificationEndpoint 落点（verification_uri 即此）。 */
    static final String DEVICE_VERIFY_PATH = "/device/verify";

    /** core 单文件 CSS 的出网路径（模板内 @{/css/jauth.css}）。 */
    static final String CSS_PATTERN = "/css/**";

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

    // ------------------------------------------------------------------ 令牌装配

    /** 默认 claims 贡献者：sub/username，用户查找接 jauth UserRepository（B2 既定接线）。 */
    @Bean
    @ConditionalOnMissingBean(ClaimsContributor.class)
    DefaultClaimsContributor defaultClaimsContributor(UserRepository userRepository) {
        return new DefaultClaimsContributor(userRepository::findByUsername);
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
     * 侧接线）。Boot 全自动配置宿主由 Boot 的 SAS JWT 自动配置供给；本装配补同一默认（同一 JWKSource 派生，
     * 即框架 OAuth2AuthorizationServerConfiguration.jwtDecoder 的公开工厂），宿主可替换。
     */
    @Bean
    @ConditionalOnMissingBean
    JwtDecoder jauthJwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    // ------------------------------------------------------------------ 页面与教学（SPEC §7）

    /** 教学层开关：属性绑定（默认开），core 三页消费。 */
    @Bean
    EducationalFlag jauthEducationalFlag(JauthHubProperties properties) {
        return properties::isEducational;
    }

    /** scope 目录：内存实现内置三枚 OIDC 标准 scope，宿主可注册自有 scope（目录 = 代码 + i18n，不建表）。 */
    @Bean
    @ConditionalOnMissingBean(ScopeCatalog.class)
    ScopeCatalog jauthScopeCatalog() {
        return new InMemoryScopeCatalog();
    }

    @Bean
    LoginController jauthLoginController(EducationalFlag educational) {
        return new LoginController(educational);
    }

    @Bean
    ConsentController jauthConsentController(
            RegisteredClientRepository clientRepository,
            ScopeCatalog scopeCatalog,
            MessageSource messageSource,
            EducationalFlag educational) {
        return new ConsentController(clientRepository, scopeCatalog, messageSource, educational);
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
            OAuth2TokenGenerator<? extends OAuth2Token> tokenGenerator)
            throws Exception {

        http.oauth2AuthorizationServer(authorizationServer -> authorizationServer
                .registeredClientRepository(registeredClientRepository)
                .authorizationService(authorizationService)
                .authorizationConsentService(authorizationConsentService)
                .authorizationServerSettings(authorizationServerSettings)
                .tokenGenerator(tokenGenerator)
                .oidc(Customizer.withDefaults())
                .authorizationEndpoint(endpoint -> endpoint.consentPage(CONSENT_PAGE_PATH)));

        OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
                http.getConfigurer(OAuth2AuthorizationServerConfigurer.class);
        RequestMatcher endpointsMatcher = authorizationServerConfigurer.getEndpointsMatcher();
        http.securityMatcher(new OrRequestMatcher(
                endpointsMatcher,
                PathPatternRequestMatcher.withDefaults().matcher(LOGIN_PATH),
                PathPatternRequestMatcher.withDefaults().matcher(CONSENT_PAGE_PATH),
                PathPatternRequestMatcher.withDefaults().matcher(DEVICE_VERIFY_PATH),
                PathPatternRequestMatcher.withDefaults().matcher(CSS_PATTERN)));

        // CORS 仅在显式配置来源时启用（默认空 = 关，SPEC §4）
        if (!properties.getCors().getAllowedOrigins().isEmpty()) {
            http.cors(Customizer.withDefaults());
        }

        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers(endpointsMatcher)
                .authenticated()
                .requestMatchers(LOGIN_PATH, CSS_PATTERN)
                .permitAll()
                .requestMatchers(CONSENT_PAGE_PATH, DEVICE_VERIFY_PATH)
                .authenticated());

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

        @Bean
        @ConditionalOnMissingBean(OAuth2AuthorizationService.class)
        FamilyAwareInMemoryAuthorizationService jauthAuthorizationService(
                InMemoryTokenFamilyService tokenFamilyService) {
            return new FamilyAwareInMemoryAuthorizationService(
                    new InMemoryOAuth2AuthorizationService(), tokenFamilyService);
        }

        @Bean
        @ConditionalOnMissingBean(OAuth2AuthorizationConsentService.class)
        InMemoryOAuth2AuthorizationConsentService jauthAuthorizationConsentService() {
            return new InMemoryOAuth2AuthorizationConsentService();
        }

        @Bean
        @ConditionalOnMissingBean(UserRepository.class)
        InMemoryUserRepository jauthUserRepository() {
            return new InMemoryUserRepository();
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

        @Bean
        @ConditionalOnMissingBean(OAuth2AuthorizationService.class)
        JauthJdbcOAuth2AuthorizationService jauthAuthorizationService(
                DataSource dataSource,
                JauthJdbcRegisteredClientRepository registeredClientRepository,
                JdbcTokenFamilyService tokenFamilyService,
                FlywayMigrationGuard migrationGuard) {
            return new JauthJdbcOAuth2AuthorizationService(
                    new JdbcTemplate(dataSource), registeredClientRepository, tokenFamilyService);
        }

        @Bean
        @ConditionalOnMissingBean(OAuth2AuthorizationConsentService.class)
        JdbcOAuth2AuthorizationConsentService jauthAuthorizationConsentService(
                DataSource dataSource,
                JauthJdbcRegisteredClientRepository registeredClientRepository,
                FlywayMigrationGuard migrationGuard) {
            return new JdbcOAuth2AuthorizationConsentService(new JdbcTemplate(dataSource), registeredClientRepository);
        }

        @Bean
        @ConditionalOnMissingBean(UserRepository.class)
        JdbcUserRepository jauthUserRepository(DataSource dataSource, FlywayMigrationGuard migrationGuard) {
            return new JdbcUserRepository(new JdbcTemplate(dataSource));
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
