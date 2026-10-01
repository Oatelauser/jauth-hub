package io.github.oatelauser.jauth.core.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

/**
 * scope 声明式校验门单测（v1.2 C4）：非注解方法/非 HandlerMethod 零开销直通；scope 原串（spring-plus
 * 权限键形态）与 SCOPE_ 前缀（rs-starter 默认映射）两态放行；缺 scope/未认证抛 AccessDeniedException
 * （生态标准异常，403 语义归宿主过滤链渲染）。
 *
 * @author oatelauser
 */
class RequiresScopeInterceptorTest {

    private final RequiresScopeInterceptor interceptor = new RequiresScopeInterceptor();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("非注解方法与非 HandlerMethod 直通零开销（无认证主体也放行）")
    void nonAnnotatedHandlersPassThrough() throws Exception {
        assertThat(this.interceptor.preHandle(
                        new MockHttpServletRequest(), new MockHttpServletResponse(), handler("plain")))
                .isTrue();
        assertThat(this.interceptor.preHandle(
                        new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()))
                .isTrue();
    }

    @Test
    @DisplayName("scope 原串 authority（spring-plus 权限键形态）放行")
    void rawScopeAuthorityPasses() throws Exception {
        authenticate("orders:read");
        assertThat(this.interceptor.preHandle(
                        new MockHttpServletRequest(), new MockHttpServletResponse(), handler("scoped")))
                .isTrue();
    }

    @Test
    @DisplayName("SCOPE_ 前缀 authority（rs-starter/Spring Security 默认映射）放行")
    void prefixedScopeAuthorityPasses() throws Exception {
        authenticate("SCOPE_orders:read");
        assertThat(this.interceptor.preHandle(
                        new MockHttpServletRequest(), new MockHttpServletResponse(), handler("scoped")))
                .isTrue();
    }

    @Test
    @DisplayName("缺 scope：AccessDeniedException 且消息带目标 scope；未认证同拒（fail-closed）")
    void missingScopeOrAuthenticationDenied() throws Exception {
        authenticate("SCOPE_other:read");
        assertThatThrownBy(() -> this.interceptor.preHandle(
                        new MockHttpServletRequest(), new MockHttpServletResponse(), handler("scoped")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("orders:read");
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> this.interceptor.preHandle(
                        new MockHttpServletRequest(), new MockHttpServletResponse(), handler("scoped")))
                .isInstanceOf(AccessDeniedException.class);
    }

    private static void authenticate(String... authorities) {
        List<SimpleGrantedAuthority> granted =
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("user", "n/a", granted));
    }

    private static HandlerMethod handler(String methodName) throws NoSuchMethodException {
        return new HandlerMethod(new DummyController(), DummyController.class.getDeclaredMethod(methodName));
    }

    /** 拦截器只认方法注解：两方法模拟受 scope 保护/普通端点。 */
    static class DummyController {

        @RequiresScope(value = "orders:read", desc = "读取订单")
        public String scoped() {
            return "ok";
        }

        public String plain() {
            return "ok";
        }
    }
}
