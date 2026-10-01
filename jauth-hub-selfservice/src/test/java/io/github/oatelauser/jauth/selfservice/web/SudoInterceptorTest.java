package io.github.oatelauser.jauth.selfservice.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.oatelauser.jauth.core.audit.AuditEvent;
import io.github.oatelauser.jauth.core.audit.AuditEventType;
import io.github.oatelauser.jauth.core.response.JauthException;
import io.github.oatelauser.jauth.core.user.InMemoryUserRepository;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.SudoGate;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.web.RequiresSudo;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

/**
 * sudo 拦截门单测（v1.2 C3）：非注解方法/非 HandlerMethod 零开销直通；新鲜放行不审计；
 * 过期/未认证 fail-closed 抛 A0515 并记 SUDO_REQUIRED 审计（detail 带目标路径）。
 *
 * @author oatelauser
 */
class SudoInterceptorTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");

    private static final String ALICE_ID = "018f0000-0000-7000-8000-0000000000cc";

    private final List<AuditEvent> auditLog = new ArrayList<>();

    private final UserRepository users = new InMemoryUserRepository();

    private SudoInterceptor interceptor;

    @BeforeEach
    void setUp() {
        this.users.save(new JauthUser(
                ALICE_ID,
                "alice",
                "{bcrypt}placeholder-not-a-real-hash",
                null,
                null,
                JauthUser.ROLE_USER,
                JauthUser.STATUS_ACTIVE,
                null,
                NOW.minusSeconds(60)));
        this.interceptor = new SudoInterceptor(
                new SudoGate(this.users, Duration.ofMinutes(15), Clock.fixed(NOW, ZoneOffset.UTC)),
                this.auditLog::add,
                this.users);
    }

    @Test
    @DisplayName("非注解方法与非 HandlerMethod 直通零开销（不触达 SudoGate、不审计）")
    void nonAnnotatedHandlersPassThrough() throws Exception {
        assertThat(this.interceptor.preHandle(
                        request("alice", "/selfservice/apps"), new MockHttpServletResponse(), handler("plain")))
                .isTrue();
        assertThat(this.interceptor.preHandle(request("alice", "/x"), new MockHttpServletResponse(), new Object()))
                .isTrue();
        assertThat(this.auditLog).isEmpty();
    }

    @Test
    @DisplayName("强认证新鲜：放行且不审计（打点可见性归 LOGIN_SUCCESS factor=webauthn）")
    void freshStrongAuthPasses() throws Exception {
        this.users.updateStrongAuthAt(ALICE_ID, NOW.minusSeconds(60));
        assertThat(this.interceptor.preHandle(
                        request("alice", "/api/profile/password"), new MockHttpServletResponse(), handler("sensitive")))
                .isTrue();
        assertThat(this.auditLog).isEmpty();
    }

    @Test
    @DisplayName("过期/未打点/未认证：A0515 + SUDO_REQUIRED 审计记路径（fail-closed）")
    void staleOrUnauthenticatedBlocked() throws Exception {
        assertThatThrownBy(() -> this.interceptor.preHandle(
                        request("alice", "/api/profile/password"), new MockHttpServletResponse(), handler("sensitive")))
                .isInstanceOf(JauthException.class);
        assertThatThrownBy(() -> this.interceptor.preHandle(
                        request(null, "/api/admin/users/x/role"), new MockHttpServletResponse(), handler("sensitive")))
                .isInstanceOf(JauthException.class);
        assertThat(this.auditLog).hasSize(2);
        assertThat(this.auditLog.get(0).type()).isEqualTo(AuditEventType.SUDO_REQUIRED);
        assertThat(this.auditLog.get(0).actorUserId()).isEqualTo(ALICE_ID);
        assertThat(this.auditLog.get(0).detail()).isEqualTo("path=/api/profile/password");
        assertThat(this.auditLog.get(1).type()).isEqualTo(AuditEventType.SUDO_REQUIRED);
        assertThat(this.auditLog.get(1).actorUserId()).isNull();
    }

    private static MockHttpServletRequest request(String remoteUser, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRemoteUser(remoteUser);
        return request;
    }

    private static HandlerMethod handler(String methodName) throws NoSuchMethodException {
        return new HandlerMethod(new DummyController(), DummyController.class.getDeclaredMethod(methodName));
    }

    /** 拦截器只认方法注解：两方法模拟敏感/普通端点。 */
    static class DummyController {

        @RequiresSudo
        public String sensitive() {
            return "ok";
        }

        public String plain() {
            return "ok";
        }
    }
}
