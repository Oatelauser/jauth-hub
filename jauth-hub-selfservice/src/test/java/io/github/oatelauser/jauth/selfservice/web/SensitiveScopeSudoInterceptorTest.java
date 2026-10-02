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
import io.github.oatelauser.jauth.core.util.UuidV7;
import io.github.oatelauser.jauth.core.web.RequiresScope;
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
 * 敏感 scope 联动门单测（v1.3 D2 新6）：sensitive 注解 + sudo TTL 内放行；TTL 外/未认证 fail-closed
 * （A0515 + SUDO_REQUIRED 审计）；非敏感/无注解零行为。当下生产面零消费端，本类即行为钉。
 *
 * @author oatelauser
 */
class SensitiveScopeSudoInterceptorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC);

    private static final String USERNAME = "alice";

    private UserRepository userRepository;

    private List<AuditEvent> published;

    private SensitiveScopeSudoInterceptor interceptor;

    @BeforeEach
    void setUp() {
        this.userRepository = new InMemoryUserRepository();
        this.userRepository.save(new JauthUser(
                UuidV7.generate().toString(),
                USERNAME,
                "{bcrypt}placeholder-not-real",
                null,
                null,
                JauthUser.ROLE_USER,
                JauthUser.STATUS_ACTIVE,
                null,
                Instant.now()));
        this.published = new ArrayList<>();
        this.interceptor = new SensitiveScopeSudoInterceptor(
                new SudoGate(this.userRepository, Duration.ofMinutes(15), CLOCK),
                this.published::add,
                this.userRepository);
    }

    @Test
    @DisplayName("敏感 scope + TTL 内：放行不打点")
    void sensitiveScopeWithFreshStrongAuthPasses() throws Exception {
        this.userRepository.updateStrongAuthAt(userId(), CLOCK.instant().minusSeconds(60));
        assertThat(this.interceptor.preHandle(request(USERNAME), new MockHttpServletResponse(), sensitiveHandler()))
                .isTrue();
        assertThat(this.published).isEmpty();
    }

    @Test
    @DisplayName("敏感 scope + TTL 外：fail-closed（A0515）+ SUDO_REQUIRED 审计")
    void sensitiveScopeWithStaleStrongAuthBlocked() throws Exception {
        this.userRepository.updateStrongAuthAt(userId(), CLOCK.instant().minusSeconds(3_600));
        assertThatThrownBy(() -> this.interceptor.preHandle(
                        request(USERNAME), new MockHttpServletResponse(), sensitiveHandler()))
                .isInstanceOf(JauthException.class);
        assertThat(this.published).hasSize(1);
        assertThat(this.published.get(0).type()).isEqualTo(AuditEventType.SUDO_REQUIRED);
        assertThat(this.published.get(0).detail()).contains("sensitive-scope");
    }

    @Test
    @DisplayName("非敏感 scope 与无注解方法：零行为直通")
    void nonSensitiveAndUnannotatedPassThrough() throws Exception {
        assertThat(this.interceptor.preHandle(request(USERNAME), new MockHttpServletResponse(), plainHandler()))
                .isTrue();
        assertThat(this.interceptor.preHandle(request(USERNAME), new MockHttpServletResponse(), unannotatedHandler()))
                .isTrue();
        assertThat(this.published).isEmpty();
    }

    private static MockHttpServletRequest request(String remoteUser) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteUser(remoteUser);
        return request;
    }

    private String userId() {
        return this.userRepository.findByUsername(USERNAME).id();
    }

    private static HandlerMethod sensitiveHandler() throws NoSuchMethodException {
        return new HandlerMethod(new DummyController(), DummyController.class.getMethod("sensitive"));
    }

    private static HandlerMethod plainHandler() throws NoSuchMethodException {
        return new HandlerMethod(new DummyController(), DummyController.class.getMethod("plain"));
    }

    private static HandlerMethod unannotatedHandler() throws NoSuchMethodException {
        return new HandlerMethod(new DummyController(), DummyController.class.getMethod("untouched"));
    }

    /** 行为钉用的哑控制器：三态方法（敏感/非敏感/无注解）。 */
    private static final class DummyController {

        @RequiresScope(value = "orders:write", sensitive = true)
        public String sensitive() {
            return "ok";
        }

        @RequiresScope("orders:read")
        public String plain() {
            return "ok";
        }

        public String untouched() {
            return "ok";
        }
    }
}
