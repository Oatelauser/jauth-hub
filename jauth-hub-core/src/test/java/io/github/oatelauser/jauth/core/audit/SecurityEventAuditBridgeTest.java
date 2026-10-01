package io.github.oatelauser.jauth.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.oatelauser.jauth.core.passkey.JauthUserEntityRepository;
import io.github.oatelauser.jauth.core.ratelimit.RateLimiter;
import io.github.oatelauser.jauth.core.user.InMemoryUserRepository;
import io.github.oatelauser.jauth.core.user.JauthUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthentication;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthenticationRequestToken;
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest;

/**
 * 认证事件桥单测（v1.2 C2）：passkey 成功/失败两形状进 LOGIN_* 审计（detail 带 factor=webauthn）；
 * 限流登记的条件语义——断言携带可解析 userHandle（→ jauth 用户）才计数，usernameless/伪造句柄不进限流；
 * 表单登录两事件的既有词形不受扩展影响。
 *
 * @author oatelauser
 */
class SecurityEventAuditBridgeTest {

    private static final String ALICE_ID = "018f0000-0000-7000-8000-0000000000aa";

    private static final Instant T0 = Instant.parse("2026-10-01T08:00:00Z");

    private final List<AuditEvent> auditLog = new ArrayList<>();

    private InMemoryUserRepository users;

    private RateLimiter rateLimiter;

    private SecurityEventAuditBridge bridge;

    @BeforeEach
    void setUp() {
        this.users = new InMemoryUserRepository();
        this.users.save(new JauthUser(
                ALICE_ID,
                "alice",
                "{bcrypt}placeholder-not-a-real-hash",
                null,
                null,
                JauthUser.ROLE_USER,
                JauthUser.STATUS_ACTIVE,
                null,
                T0));
        // 阈值 3：三次失败即锁——测试断言登锁状态差分的最小配置
        this.rateLimiter = new RateLimiter(1000, 3, Duration.ofMinutes(15), Clock.fixed(T0, ZoneOffset.UTC));
        this.bridge = new SecurityEventAuditBridge(this.auditLog::add, this.rateLimiter, this.users);
    }

    @Test
    @DisplayName("passkey 成功：LOGIN_SUCCESS 审计带 factor=webauthn 与用户反查，失败计数清零")
    void webauthnSuccessAuditedAndClearsFailureCounter() {
        this.rateLimiter.onLoginFailure("alice");
        this.rateLimiter.onLoginFailure("alice");

        this.bridge.onLoginSuccess(new AuthenticationSuccessEvent(webauthnAuthentication()));

        assertThat(this.auditLog).hasSize(1);
        AuditEvent event = this.auditLog.get(0);
        assertThat(event.type()).isEqualTo(AuditEventType.LOGIN_SUCCESS);
        assertThat(event.actorUserId()).isEqualTo(ALICE_ID);
        assertThat(event.targetId()).isEqualTo("alice");
        assertThat(event.detail()).isEqualTo("factor=webauthn");
        // 成功清零失败计数（RateLimiter 语义：锁定期不因成功提前解除，清的是计数）：2 失败 + 成功 + 1 失败 ≠ 3 连错
        this.rateLimiter.onLoginFailure("alice");
        assertThat(this.rateLimiter.isLoginLocked("alice")).isFalse();
    }

    @Test
    @DisplayName("passkey 失败（userHandle 可解析）：LOGIN_FAILED 审计 + 达阈值进登录锁")
    void webauthnFailureWithResolvableUserHandleCountsLockout() {
        for (int i = 0; i < 3; i++) {
            this.bridge.onLoginFailure(new AuthenticationFailureBadCredentialsEvent(
                    webauthnRequestToken(JauthUserEntityRepository.userHandle(ALICE_ID)),
                    new BadCredentialsException("assertion rejected")));
        }

        assertThat(this.rateLimiter.isLoginLocked("alice")).isTrue();
        AuditEvent event = this.auditLog.get(2);
        assertThat(event.type()).isEqualTo(AuditEventType.LOGIN_FAILED);
        assertThat(event.actorUserId()).isEqualTo(ALICE_ID);
        assertThat(event.targetId()).isEqualTo("alice");
        assertThat(event.detail()).isEqualTo("factor=webauthn; cause=BadCredentialsException");
    }

    @Test
    @DisplayName("passkey 失败（usernameless / 伪造句柄）：只记审计不进限流——不造假桶")
    void webauthnFailureWithoutResolvableUsernameSkipsRateLimit() {
        for (int i = 0; i < 6; i++) {
            this.bridge.onLoginFailure(new AuthenticationFailureBadCredentialsEvent(
                    webauthnRequestToken(null), new BadCredentialsException("no user handle")));
            this.bridge.onLoginFailure(new AuthenticationFailureBadCredentialsEvent(
                    webauthnRequestToken(new Bytes("no-such-user".getBytes())),
                    new BadCredentialsException("unknown handle")));
        }

        // 两种"解析不出用户"的失败都不得登记限流（也不会以空串/原始句柄键控）
        assertThat(this.rateLimiter.isLoginLocked("alice")).isFalse();
        assertThat(this.auditLog).hasSize(12);
        assertThat(this.auditLog.get(0).type()).isEqualTo(AuditEventType.LOGIN_FAILED);
        assertThat(this.auditLog.get(0).actorUserId()).isNull();
        assertThat(this.auditLog.get(0).targetId()).isNull();
        assertThat(this.auditLog.get(0).detail()).isEqualTo("factor=webauthn; cause=BadCredentialsException");
    }

    @Test
    @DisplayName("表单登录词形不变：成功 detail 仍为 null，失败仍只有 cause=")
    void formLoginAuditShapeUnchanged() {
        UsernamePasswordAuthenticationToken form = UsernamePasswordAuthenticationToken.authenticated(
                "alice", "N/A", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        this.bridge.onLoginSuccess(new AuthenticationSuccessEvent(form));
        this.bridge.onLoginFailure(
                new AuthenticationFailureBadCredentialsEvent(form, new BadCredentialsException("wrong password")));

        assertThat(this.auditLog).hasSize(2);
        assertThat(this.auditLog.get(0).type()).isEqualTo(AuditEventType.LOGIN_SUCCESS);
        assertThat(this.auditLog.get(0).detail()).isNull();
        assertThat(this.auditLog.get(1).type()).isEqualTo(AuditEventType.LOGIN_FAILED);
        assertThat(this.auditLog.get(1).detail()).isEqualTo("cause=BadCredentialsException");
    }

    private static WebAuthnAuthentication webauthnAuthentication() {
        return new WebAuthnAuthentication(
                ImmutablePublicKeyCredentialUserEntity.builder()
                        .id(JauthUserEntityRepository.userHandle(ALICE_ID))
                        .name("alice")
                        .displayName("alice")
                        .build(),
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    /** 失败事件形状：请求 token 的 principal = 断言携带的 userHandle（Bytes，可 null）。 */
    private static WebAuthnAuthenticationRequestToken webauthnRequestToken(Bytes userHandle) {
        AuthenticatorAssertionResponse assertion = AuthenticatorAssertionResponse.builder()
                .clientDataJSON(new Bytes(new byte[] {1}))
                .authenticatorData(new Bytes(new byte[] {2}))
                .signature(new Bytes(new byte[] {3}))
                .userHandle(userHandle)
                .build();
        PublicKeyCredential<AuthenticatorAssertionResponse> credential =
                PublicKeyCredential.<AuthenticatorAssertionResponse>builder()
                        .id("placeholder")
                        .rawId(new Bytes(new byte[] {4}))
                        .type(PublicKeyCredentialType.PUBLIC_KEY)
                        .response(assertion)
                        .build();
        return new WebAuthnAuthenticationRequestToken(
                new RelyingPartyAuthenticationRequest(mock(PublicKeyCredentialRequestOptions.class), credential));
    }
}
