package io.github.oatelauser.jauth.resourceserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionException;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * 内省解析直连面（MockRestServiceServer 桩端点）：请求形状（Basic 机密客户端凭证 + token 表单）、
 * active/inactive/orgs 字段→authorities/attributes（07 票富化照单全收）、prefix 两态、故障语义。
 *
 * @author oatelauser
 */
class JauthOpaqueTokenIntrospectorTest {

    private static final String INTROSPECTION_URI = "https://jauth.example.com/introspect";

    private static final String CLIENT_ID = "rs-client";

    /** 占位形状 secret（约定：测试凭证不使用真实形状）。 */
    private static final String PLACEHOLDER_SECRET = "placeholder-test-secret-not-real";

    private record Wired(MockRestServiceServer server, JauthOpaqueTokenIntrospector introspector) {}

    private static Wired wire(String authorityPrefix) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Wired(
                server,
                new JauthOpaqueTokenIntrospector(
                        builder.build(), INTROSPECTION_URI, CLIENT_ID, PLACEHOLDER_SECRET, authorityPrefix));
    }

    private static String basicAuth(String clientId, String secret) {
        return "Basic "
                + Base64.getEncoder().encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    private static MultiValueMap<String, String> form(String token) {
        MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
        map.add("token", token);
        return map;
    }

    @Test
    @DisplayName("active 令牌：请求形状（POST + Basic 凭证 + token 表单）与富化字段全量转 principal")
    void introspectActiveToken() {
        String json =
                """
                {"active":true,"scope":"read:user write:repo","sub":"0192-user-id","username":"alice",
                 "orgs":[{"id":"org-1","role":"OWNER"}],"client_id":"rs-client"}
                """;
        Wired wired = wire("SCOPE_");
        wired.server()
                .expect(requestTo(INTROSPECTION_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", basicAuth(CLIENT_ID, PLACEHOLDER_SECRET)))
                .andExpect(content().formData(form("token-A")))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        var principal = wired.introspector().introspect("token-A");

        assertThat(principal.getName()).isEqualTo("0192-user-id");
        assertThat((Object) principal.getAttribute("username")).isEqualTo("alice");
        assertThat((Object) principal.getAttribute("scope")).isEqualTo("read:user write:repo");
        assertThat((List<?>) principal.getAttribute("orgs")).hasSize(1);
        assertThat(principal.getAuthorities())
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("SCOPE_read:user", "SCOPE_write:repo");
        wired.server().verify();
    }

    @Test
    @DisplayName("prefix 空（默认）：scope 原样作 authority")
    void emptyPrefixKeepsScopeVerbatim() {
        Wired wired = wire("");
        wired.server()
                .expect(requestTo(INTROSPECTION_URI))
                .andRespond(withSuccess(
                        "{\"active\":true,\"scope\":\"read:user\",\"sub\":\"u1\"}", MediaType.APPLICATION_JSON));

        var principal = wired.introspector().introspect("token-B");

        assertThat(principal.getAuthorities()).extracting(Object::toString).containsExactly("read:user");
    }

    @Test
    @DisplayName("scope 含连续空白：容错切分不产生空 authority")
    void blankTolerantScopeSplit() {
        Wired wired = wire("");
        wired.server()
                .expect(requestTo(INTROSPECTION_URI))
                .andRespond(withSuccess(
                        "{\"active\":true,\"scope\":\"read:user   write:repo\",\"sub\":\"u1\"}",
                        MediaType.APPLICATION_JSON));

        var principal = wired.introspector().introspect("token-B2");

        assertThat(principal.getAuthorities()).extracting(Object::toString).containsExactly("read:user", "write:repo");
    }

    @Test
    @DisplayName("scope 缺失：authorities 为空，不抛错")
    void missingScopeYieldsNoAuthorities() {
        Wired wired = wire("SCOPE_");
        wired.server()
                .expect(requestTo(INTROSPECTION_URI))
                .andRespond(withSuccess(
                        "{\"active\":true,\"sub\":\"u1\",\"client_id\":\"rs-client\"}", MediaType.APPLICATION_JSON));

        var principal = wired.introspector().introspect("token-C");

        assertThat(principal.getAuthorities()).isEmpty();
    }

    @Test
    @DisplayName("active=false：抛 Inactive（可负缓存的确定性判定）")
    void inactiveTokenThrowsInactive() {
        Wired wired = wire("SCOPE_");
        wired.server()
                .expect(requestTo(INTROSPECTION_URI))
                .andRespond(withSuccess("{\"active\":false}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> wired.introspector().introspect("token-D"))
                .isInstanceOf(JauthOpaqueTokenIntrospector.InactiveOAuth2TokenException.class)
                .hasMessage("Token is not active");
    }

    @Test
    @DisplayName("active 缺失：按 RFC 7662 视为不活跃")
    void missingActiveTreatedAsInactive() {
        Wired wired = wire("");
        wired.server().expect(requestTo(INTROSPECTION_URI)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> wired.introspector().introspect("token-E"))
                .isInstanceOf(JauthOpaqueTokenIntrospector.InactiveOAuth2TokenException.class);
    }

    @Test
    @DisplayName("无 sub 的 client 令牌：principal name 退 client_id")
    void clientTokenFallsBackToClientId() {
        Wired wired = wire("");
        wired.server()
                .expect(requestTo(INTROSPECTION_URI))
                .andRespond(withSuccess("{\"active\":true,\"client_id\":\"rs-client\"}", MediaType.APPLICATION_JSON));

        var principal = wired.introspector().introspect("token-F");

        assertThat(principal.getName()).isEqualTo("rs-client");
    }

    @Test
    @DisplayName("端点故障（非 2xx）：普通内省异常（不可负缓存），非 Inactive 语义")
    void endpointFailureThrowsPlainIntrospectionException() {
        Wired wired = wire("");
        wired.server().expect(requestTo(INTROSPECTION_URI)).andRespond(withServerError());

        assertThatThrownBy(() -> wired.introspector().introspect("token-G"))
                .isInstanceOf(OAuth2IntrospectionException.class)
                .isNotInstanceOf(JauthOpaqueTokenIntrospector.InactiveOAuth2TokenException.class);
    }
}
