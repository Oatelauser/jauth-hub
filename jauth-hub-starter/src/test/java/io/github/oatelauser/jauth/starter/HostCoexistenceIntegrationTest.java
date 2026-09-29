package io.github.oatelauser.jauth.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.test.context.TestPropertySource;

/**
 * 宿主共存集成测试（SPEC §2 宿主链共存四规则）：宿主测试配置再注册一条自己的链（/api/**）——两链并存、
 * 各认各的路径、jauth 链序位（默认 100）在宿主链之前。
 *
 * @author oatelauser
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        classes = {JauthHubStarterTestApplication.class, HostCoexistenceIntegrationTest.HostChainConfig.class})
@TestPropertySource(
        properties = {
            "spring.flyway.enabled=false",
            "spring.autoconfigure.exclude=io.github.oatelauser.springplus.web.autoconfigure.ExceptionHandlingAutoConfiguration,"
                    + "io.github.oatelauser.springplus.web.autoconfigure.SpringPlusWebAutoConfiguration",
            "jauth-hub.clients[0].client-id=coexist-client",
            "jauth-hub.clients[0].client-name=Coexist Client",
            "jauth-hub.clients[0].client-secret=coexist-secret",
            "jauth-hub.clients[0].grant-types[0]=authorization_code",
            "jauth-hub.clients[0].redirect-uris[0]=https://example.com/cb",
            "jauth-hub.clients[0].scopes[0]=openid"
        })
class HostCoexistenceIntegrationTest {

    @Autowired
    private List<SecurityFilterChain> filterChains;

    @Test
    @DisplayName("两链共存：jauth 协议链 + 宿主 /api/** 链，Boot 默认链仍让位")
    void bothChainsCoexist() {
        assertThat(filterChains).hasSize(2);
        SecurityFilterChain jauthChain = jauthChain();
        SecurityFilterChain hostChain = hostChain();
        assertThat(jauthChain).isNotNull();
        assertThat(hostChain).isNotNull();
    }

    @Test
    @DisplayName("路由不串：/api/** 归宿主链，协议端点归 jauth 链（互不吞请求）")
    void routingDoesNotCross() {
        assertThat(jauthChain().matches(request("GET", "/api/foo"))).isFalse();
        assertThat(hostChain().matches(request("GET", "/api/foo"))).isTrue();
        assertThat(hostChain().matches(request("POST", "/oauth2/token"))).isFalse();
        assertThat(jauthChain().matches(request("POST", "/oauth2/token"))).isTrue();
        assertThat(jauthChain().matches(request("GET", "/login"))).isTrue();
    }

    @Test
    @DisplayName("序位：注入序 jauth 链（Ordered=100）在宿主链（@Order(200)）之前")
    void jauthChainOrdersBeforeHostChain() {
        SecurityFilterChain first = filterChains.get(0);
        SecurityFilterChain second = filterChains.get(1);
        assertThat(first).isSameAs(jauthChain());
        assertThat(second).isSameAs(hostChain());
        assertThat(((Ordered) first).getOrder()).isEqualTo(100);
    }

    private SecurityFilterChain jauthChain() {
        return filterChains.stream()
                .filter(chain -> chain.matches(request("POST", "/oauth2/token")))
                .findFirst()
                .orElse(null);
    }

    private SecurityFilterChain hostChain() {
        return filterChains.stream()
                .filter(chain -> chain.matches(request("GET", "/api/foo")))
                .findFirst()
                .orElse(null);
    }

    private static MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    /**
     * 宿主自链（模拟宿主嵌 starter 的真实形态）：认领 /api/**、httpBasic、序位 200。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class HostChainConfig {

        @Bean
        @Order(200)
        SecurityFilterChain hostApiChain(HttpSecurity http) throws Exception {
            http.securityMatcher(PathPatternRequestMatcher.withDefaults().matcher("/api/**"))
                    .authorizeHttpRequests(
                            authorize -> authorize.requestMatchers("/api/**").authenticated())
                    .httpBasic(Customizer.withDefaults());
            return http.build();
        }
    }
}
