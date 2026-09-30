package io.github.oatelauser.jauth.selfservice.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.oatelauser.jauth.core.scope.InMemoryScopeCatalog;
import io.github.oatelauser.jauth.core.scope.ScopeCatalog;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * 个人应用注册服务契约（内存/JDBC 两实现共用，照 AbstractPatServiceContractTest 模式）。
 *
 * <p>关键断言面：注册 → 列表可见；机密应用 secret 一次性（明文不落库、编码值可 matches）；公开应用无 secret
 * 且不发 refresh grant（SPEC §6）；PKCE 强制 + consent 开（个人应用无 ceiling，封顶交 consent 页）；redirect
 * 白名单校验拒非 http(s)/带 fragment。
 *
 * @author oatelauser
 */
abstract class AbstractOwnedAppServiceContractTest {

    /** 占位用户 id（owner 列 CHAR(36)，UUID 形状的 36 字符即满位，无补空白问题）。 */
    protected static final String USER_ID = "0192ab00-0000-7000-8000-00000000000a";

    protected static final String OTHER_USER_ID = "0192ab00-0000-7000-8000-00000000000b";

    protected static final Instant T0 = Instant.parse("2026-09-30T10:00:00Z");

    protected static final Set<String> REDIRECTS = Set.of("https://app.example.com/callback");

    /** 受测服务（构造于固定时钟 T0）。 */
    protected abstract OwnedAppService service();

    /** 落位后的 client 读取面（断言存储侧形态：secret 编码、设置、grants）。 */
    protected abstract RegisteredClientRepository clientRepository();

    /** 与受测服务同源的编码器（matches 验证明文与存储值的一致性）。 */
    protected abstract PasswordEncoder passwordEncoder();

    @Test
    void registerConfidentialAppListsItAndKeepsSecretOneTime() {
        OwnedAppService.Registration registration = service().register(USER_ID, "CI 看板", REDIRECTS, true);

        assertThat(registration.plaintextSecret()).isNotBlank();
        assertThat(registration.app().clientId()).startsWith("app_");
        assertThat(registration.app().confidential()).isTrue();
        assertThat(registration.app().redirectUris()).containsExactlyElementsOf(REDIRECTS);

        RegisteredClient stored =
                clientRepository().findByClientId(registration.app().clientId());
        assertThat(stored).isNotNull();
        // 明文不落库：存的是编码值，且与一次性明文 matches（框架 client_secret 编码纪律）
        assertThat(stored.getClientSecret()).isNotEqualTo(registration.plaintextSecret());
        assertThat(passwordEncoder().matches(registration.plaintextSecret(), stored.getClientSecret()))
                .isTrue();
        assertThat(stored.getClientSettings().isRequireProofKey()).as("PKCE 强制").isTrue();
        assertThat(stored.getClientSettings().isRequireAuthorizationConsent())
                .as("个人应用封顶交 consent 页")
                .isTrue();
        assertThat(stored.getAuthorizationGrantTypes())
                .contains(AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN);
        assertThat(stored.getScopes()).containsExactlyInAnyOrder("openid", "profile", "email");

        assertThat(service().list(USER_ID)).singleElement().satisfies(app -> {
            assertThat(app.clientId()).isEqualTo(registration.app().clientId());
            assertThat(app.name()).isEqualTo("CI 看板");
            // 列表行不含 secret（OwnedApp 无该字段，toString 全组件证明）
            assertThat(app.toString()).doesNotContain(registration.plaintextSecret());
        });
    }

    @Test
    void registerPublicAppHasNoSecretAndNoRefreshGrant() {
        OwnedAppService.Registration registration = service().register(USER_ID, "移动端", REDIRECTS, false);

        assertThat(registration.plaintextSecret()).isNull();
        RegisteredClient stored =
                clientRepository().findByClientId(registration.app().clientId());
        assertThat(stored.getClientSecret()).isNull();
        // SPEC §6：公开客户端不发 refresh token——授权类型只有授权码
        assertThat(stored.getAuthorizationGrantTypes()).containsExactly(AuthorizationGrantType.AUTHORIZATION_CODE);
        assertThat(stored.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(service().list(USER_ID).get(0).confidential()).isFalse();
    }

    @Test
    void listIsScopedToOwner() {
        service().register(USER_ID, "我的应用", REDIRECTS, false);
        service().register(OTHER_USER_ID, "他人应用", REDIRECTS, false);

        assertThat(service().list(USER_ID))
                .extracting(OwnedAppService.OwnedApp::name)
                .containsExactly("我的应用");
    }

    @Test
    void registerRejectsInvalidRedirectUrisAtTrustBoundary() {
        // 服务层兜底（控制器 A0510 之外的第二道门）：非 URL / 非 http(s) / 带 fragment 皆拒
        assertThatThrownBy(() -> service().register(USER_ID, "x", Set.of("not a url"), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service().register(USER_ID, "x", Set.of("ftp://a.example.com/cb"), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service().register(USER_ID, "x", Set.of("https://a.example.com/cb#frag"), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 解析器单源行为：多行文本 → 校验列表（控制器与实现共用此规则）。 */
    @Test
    void parseRedirectUrisSplitsTrimsAndValidates() {
        assertThat(OwnedAppService.parseRedirectUris(
                        "  https://a.example.com/cb \n\n" + "http://localhost:8080/callback\r\n"))
                .containsExactly("https://a.example.com/cb", "http://localhost:8080/callback");
        assertThat(OwnedAppService.parseRedirectUris(null)).isEmpty();
        assertThatThrownBy(() -> OwnedAppService.parseRedirectUris("https://a.example.com/cb\njavascript:alert(1)"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 固定时钟（T0）：createdAt 断言可重现。 */
    protected static Clock fixedClock() {
        return Clock.fixed(T0, ZoneOffset.UTC);
    }

    /** 目录全集（InMemoryScopeCatalog 内置三枚；契约断言 allowed scopes = 目录全集）。 */
    protected static ScopeCatalog scopeCatalog() {
        return new InMemoryScopeCatalog();
    }

    /** 目录名集合（实现侧装配对照用）。 */
    protected static Set<String> catalogScopeNames() {
        return scopeCatalog().all().stream()
                .map(definition -> definition.name())
                .collect(Collectors.toSet());
    }
}
