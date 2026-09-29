package io.github.oatelauser.jauth.resourceserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.OpaqueTokenAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 框架 {@link OpaqueTokenAuthenticationProvider} 兼容性集成测试（B6-fix 缺陷 2）：该 provider 对
 * iat/exp 属性直接强转 Instant，内省端点返回数值型时间 claim 时原样透传必 ClassCastException
 * （内省成功的 Bearer 请求全 500）。本测试真实走框架 provider（非自解析断言），证明归一化后全链路可用。
 *
 * @author oatelauser
 */
class OpaqueTokenAuthenticationProviderCompatibilityTest {

    private static final String INTROSPECTION_URI = "https://jauth.example.com/introspect";

    private static final String CLIENT_ID = "rs-client";

    /** 占位形状 secret（约定：测试凭证不使用真实形状）。 */
    private static final String PLACEHOLDER_SECRET = "placeholder-test-secret-not-real";

    private record Wired(MockRestServiceServer server, AuthenticationProvider provider) {}

    private static Wired wire(String introspectionResponse) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(INTROSPECTION_URI))
                .andRespond(withSuccess(introspectionResponse, MediaType.APPLICATION_JSON));
        OpaqueTokenIntrospector introspector = new JauthOpaqueTokenIntrospector(
                builder.build(), INTROSPECTION_URI, CLIENT_ID, PLACEHOLDER_SECRET, "SCOPE_");
        return new Wired(server, new OpaqueTokenAuthenticationProvider(introspector));
    }

    @Test
    @DisplayName("数值型 iat/exp：框架 provider 全链路认证成功，时间属性为 Instant（修复前此处 CCE）")
    void numericTimeClaimsAuthenticateThroughFrameworkProvider() {
        // RFC 7662 正典形态：exp/iat 为 JSON 数值（Jackson 反序列化为 Integer）
        Wired wired = wire(
                """
                {"active":true,"sub":"0192-user-id","username":"alice",
                 "scope":"read:user","client_id":"rs-client",
                 "iat":1790000000,"exp":1790003600}
                """);

        Authentication result = wired.provider().authenticate(new BearerTokenAuthenticationToken("token-numeric"));

        assertThat(result).isInstanceOf(BearerTokenAuthentication.class);
        BearerTokenAuthentication bearer = (BearerTokenAuthentication) result;
        assertThat(bearer.getToken().getIssuedAt()).isEqualTo(Instant.ofEpochSecond(1790000000));
        assertThat(bearer.getToken().getExpiresAt()).isEqualTo(Instant.ofEpochSecond(1790003600));
        assertThat(bearer.getName()).isEqualTo("0192-user-id");
        org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal principal =
                (org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal) bearer.getPrincipal();
        assertThat((Object) principal.getAttribute("username")).isEqualTo("alice");
        assertThat(bearer.getAuthorities())
                .extracting(Object::toString)
                .contains("SCOPE_read:user"); // 框架另附加 FACTOR_BEARER 因子授权（Security 7 auth_time 模型）
    }

    @Test
    @DisplayName("字符串形时间 claim（数字串 / ISO-8601）：同样归一为 Instant 走通框架 provider")
    void stringTimeClaimsAlsoAuthenticate() {
        Wired wired = wire(
                """
                {"active":true,"sub":"0192-user-id","username":"alice",
                 "iat":"1790000000","exp":"2030-05-15T10:00:00Z"}
                """);

        Authentication result = wired.provider().authenticate(new BearerTokenAuthenticationToken("token-string"));

        BearerTokenAuthentication bearer = (BearerTokenAuthentication) result;
        assertThat(bearer.getToken().getIssuedAt()).isEqualTo(Instant.ofEpochSecond(1790000000));
        assertThat(bearer.getToken().getExpiresAt()).isEqualTo(Instant.parse("2030-05-15T10:00:00Z"));
    }

    @Test
    @DisplayName("不可解析的时间 claim：弃值不炸框架 provider（其余属性照常）")
    void garbageTimeClaimIsDroppedNotFatal() {
        Wired wired = wire("{\"active\":true,\"sub\":\"0192-user-id\",\"exp\":\"not-a-time\"}");

        Authentication result = wired.provider().authenticate(new BearerTokenAuthenticationToken("token-garbage"));

        BearerTokenAuthentication bearer = (BearerTokenAuthentication) result;
        assertThat(bearer.getToken().getExpiresAt())
                .as("弃值后 exp 缺省为 null，无 CCE")
                .isNull();
        assertThat(bearer.getName()).isEqualTo("0192-user-id");
    }
}
