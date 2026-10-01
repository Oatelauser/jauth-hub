package io.github.oatelauser.jauth.selfservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.oatelauser.jauth.core.client.InMemoryClientOwnerResolver;
import io.github.oatelauser.jauth.core.client.JauthJdbcRegisteredClientRepository;
import io.github.oatelauser.jauth.core.response.DefaultResponseRenderer;
import io.github.oatelauser.jauth.core.response.ResponseRenderer;
import io.github.oatelauser.jauth.core.scope.InMemoryScopeCatalog;
import io.github.oatelauser.jauth.core.scope.ScopeCatalog;
import io.github.oatelauser.jauth.core.user.InMemoryUserRepository;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * 自助装配矩阵：jdbc 门控（PAT/看板服务只在 jdbc 注册）、memory 形态（控制器在、PAT/看板服务缺、页面渲染提示态；
 * 我的应用服务 memory 可用——B10 与 PAT 的门控差异）、无 starter 探针（RegisteredClientRepository 缺席时整组
 * 让位）、selfservice i18n basename 可解析。
 *
 * @author oatelauser
 */
class JauthSelfServiceAutoConfigurationTest {

    /** 页面基建 bean 全集（不含 starter 探针与 jdbc 数据面，按测试逐个补）。 */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JauthSelfServiceAutoConfiguration.class))
            .withBean(OAuth2AuthorizationService.class, () -> mock(OAuth2AuthorizationService.class))
            .withBean(OAuth2AuthorizationConsentService.class, () -> mock(OAuth2AuthorizationConsentService.class))
            .withBean(ScopeCatalog.class, InMemoryScopeCatalog::new)
            .withBean(UserRepository.class, InMemoryUserRepository::new)
            .withBean(EducationalFlag.class, () -> EducationalFlag.ON)
            .withBean(ResponseRenderer.class, DefaultResponseRenderer::new)
            .withBean(PasswordEncoder.class, BCryptPasswordEncoder::new)
            .withBean(MessageSource.class, JauthSelfServiceAutoConfigurationTest::messageSource)
            .withBean(Clock.class, () -> Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

    @Test
    void jdbcModeRegistersPatAndDashboardServices() {
        // JauthJdbc 仓储 mock 即 starter 探针（instance type 满足 OnBean(RegisteredClientRepository)），
        // 不再另挂接口 mock——两个 RegisteredClientRepository bean 会打爆按类型注入
        this.runner
                .withPropertyValues("jauth-hub.storage=jdbc")
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(
                        JauthJdbcRegisteredClientRepository.class,
                        () -> mock(JauthJdbcRegisteredClientRepository.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(PatService.class);
                    assertThat(context).hasSingleBean(JdbcPatService.class);
                    assertThat(context).hasSingleBean(AuthorizedAppService.class);
                    assertThat(context).hasSingleBean(OwnedAppService.class);
                    assertThat(context).hasSingleBean(JdbcOwnedAppService.class);
                    assertThat(context).hasSingleBean(PatController.class);
                    assertThat(context).hasSingleBean(AuthorizedAppsController.class);
                    assertThat(context).hasSingleBean(PasskeyController.class);
                    assertThat(context).hasSingleBean(MyAppsController.class);
                    // B11 org 三页：领域 bean 缺席（本 runner 无 starter 域件）也不拦控制器注册，页面提示态
                    assertThat(context).hasSingleBean(MyOrgsController.class);
                    assertThat(context).hasSingleBean(OrgInstallationsController.class);
                    assertThat(context).hasSingleBean(OrgAppsController.class);
                    assertThat(context).hasBean("jauthSelfServiceViewResolver");
                });
    }

    @Test
    void memoryModeKeepsPagesButSkipsServices() {
        withStarterProbe(this.runner)
                .withPropertyValues("jauth-hub.storage=memory")
                .withBean(InMemoryClientOwnerResolver.class, InMemoryClientOwnerResolver::new)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(PatService.class);
                    assertThat(context).doesNotHaveBean(AuthorizedAppService.class);
                    // 我的应用（B10）：memory 模式可用（client 入框架内存仓库 + owner 登记表）
                    assertThat(context).hasSingleBean(OwnedAppService.class);
                    assertThat(context).hasSingleBean(InMemoryOwnedAppService.class);
                    assertThat(context).hasSingleBean(PatController.class);
                    assertThat(context).hasSingleBean(AuthorizedAppsController.class);
                    assertThat(context).hasSingleBean(PasskeyController.class);
                    assertThat(context).hasSingleBean(MyAppsController.class);
                    assertThat(context).hasSingleBean(MyOrgsController.class);
                    assertThat(context).hasSingleBean(OrgInstallationsController.class);
                    assertThat(context).hasSingleBean(OrgAppsController.class);
                });
    }

    @Test
    void defaultStoragePropertyBehavesAsMemory() {
        withStarterProbe(this.runner).run(context -> {
            assertThat(context).doesNotHaveBean(PatService.class);
            assertThat(context).hasSingleBean(PatController.class);
        });
    }

    @Test
    void withoutStarterProbePagesBackOff() {
        this.runner
                .withPropertyValues("jauth-hub.storage=jdbc")
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .run(context -> assertThat(context).doesNotHaveBean(PatController.class));
    }

    /** starter 在场探针：页面控制器组以 RegisteredClientRepository（starter 两模式都供给）为条件。 */
    private static ApplicationContextRunner withStarterProbe(ApplicationContextRunner base) {
        return base.withBean(RegisteredClientRepository.class, () -> mock(RegisteredClientRepository.class));
    }

    @Test
    void selfServiceBundlesResolveThroughLocalBasename() {
        MessageSource messages = JauthSelfServiceAutoConfigurationTest.messageSource();
        assertThat(messages.getMessage("jauth.pat.title", null, Locale.SIMPLIFIED_CHINESE))
                .isEqualTo("个人访问令牌");
        assertThat(messages.getMessage("jauth.apps.title", null, Locale.SIMPLIFIED_CHINESE))
                .isEqualTo("已授权应用");
        assertThat(messages.getMessage("jauth.myapps.title", null, Locale.SIMPLIFIED_CHINESE))
                .isEqualTo("我的应用");
        assertThat(messages.getMessage("jauth.pat.unnamed", null, Locale.SIMPLIFIED_CHINESE))
                .isEqualTo("未命名");
    }

    private static MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("io/github/oatelauser/jauth/selfservice/i18n/messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        return source;
    }
}
