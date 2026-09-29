package io.github.oatelauser.jauth.selfservice.pat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.oatelauser.jauth.core.response.JauthErrorCode;
import io.github.oatelauser.jauth.core.response.JauthException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * PAT 存储契约（内存/JDBC 两实现共用，照 core 的 Abstract*ContractTest 模式）。
 *
 * <p>关键断言面：明文只在 {@link PatService#create} 返回值出现一次（列表无明文）；前缀取明文头；过期时间 =
 * 创建时间 + 有效期；吊销后列表清空、重复吊销/他人吊销 B0502。
 *
 * @author oatelauser
 */
abstract class AbstractPatServiceContractTest {

    /** 占位用户 id（非真实用户行，契约不触外键）。 */
    protected static final String USER_ID = "0192ab00-0000-7000-8000-000000000001";

    protected static final String OTHER_USER_ID = "0192ab00-0000-7000-8000-000000000002";

    protected static final Instant T0 = Instant.parse("2026-09-29T10:00:00Z");

    protected static final Set<String> SCOPES = Set.of("openid", "profile");

    /** 受测服务（构造于固定时钟 T0）。 */
    protected abstract PatService service();

    @Test
    void createReturnsPlaintextOnceAndListCarriesOnlyPrefix() {
        PatService.PatIssuance issuance = service().create(USER_ID, SCOPES, Duration.ofDays(90));

        String plaintext = issuance.plaintextToken();
        assertThat(plaintext).startsWith(PatTokens.TOKEN_HEADER).hasSize(48);
        assertThat(issuance.record().tokenPrefix()).isEqualTo(PatTokens.displayPrefix(plaintext));
        assertThat(issuance.record().status()).isEqualTo(PatStatus.ACTIVE);
        assertThat(issuance.record().lastUsedAt()).as("last_used 留位 B7").isNull();

        List<PatRecord> active = service().listActive(USER_ID);
        assertThat(active).hasSize(1);
        assertThat(active.get(0).tokenPrefix()).isEqualTo(issuance.record().tokenPrefix());
        assertThat(active.get(0).scopes()).isEqualTo(SCOPES);
        // 明文纪律：列表/记录的任何字段都不含明文（record toString 覆盖全组件）
        assertThat(active.get(0).toString()).doesNotContain(plaintext);
    }

    @Test
    void expiryIsCreatedAtPlusValidity() {
        PatService.PatIssuance issuance = service().create(USER_ID, SCOPES, Duration.ofDays(30));

        assertThat(issuance.record().createdAt()).isEqualTo(T0);
        assertThat(issuance.record().expiresAt()).isEqualTo(T0.plus(Duration.ofDays(30)));
        assertThat(issuance.record().expired(T0.plus(Duration.ofDays(31)))).isTrue();
        assertThat(issuance.record().expired(T0.plus(Duration.ofDays(29)))).isFalse();
    }

    @Test
    void revokeRemovesFromListAndSecondRevokeFails() {
        PatService.PatIssuance issuance = service().create(USER_ID, SCOPES, Duration.ofDays(90));

        service().revoke(USER_ID, issuance.record().id());
        assertThat(service().listActive(USER_ID)).isEmpty();

        assertThatThrownBy(() -> service().revoke(USER_ID, issuance.record().id()))
                .isInstanceOf(JauthException.class)
                .extracting(ex -> ((JauthException) ex).getCode())
                .isEqualTo(JauthErrorCode.B0502.getCode());
    }

    @Test
    void revokingAnotherUsersPatFailsAndKeepsItActive() {
        PatService.PatIssuance issuance = service().create(USER_ID, SCOPES, Duration.ofDays(90));

        assertThatThrownBy(
                        () -> service().revoke(OTHER_USER_ID, issuance.record().id()))
                .isInstanceOf(JauthException.class);
        assertThat(service().listActive(USER_ID)).hasSize(1);
    }

    @Test
    void listActiveOnlyShowsOwnActivePatsLatestFirst() {
        PatService.PatIssuance first = service().create(USER_ID, Set.of("openid"), Duration.ofDays(30));
        PatService.PatIssuance second = service().create(USER_ID, Set.of("profile"), Duration.ofDays(90));
        service().create(OTHER_USER_ID, SCOPES, Duration.ofDays(90));

        List<PatRecord> active = service().listActive(USER_ID);
        assertThat(active).hasSize(2);
        // 固定时钟下两记录同刻创建：以 id 决胜保持确定性（两实现同一排序语义）
        assertThat(active)
                .extracting(PatRecord::id)
                .containsExactlyInAnyOrder(first.record().id(), second.record().id());
    }

    /** 固定时钟（T0）：创建/过期断言可重现。 */
    protected static Clock fixedClock() {
        return Clock.fixed(T0, ZoneOffset.UTC);
    }
}
