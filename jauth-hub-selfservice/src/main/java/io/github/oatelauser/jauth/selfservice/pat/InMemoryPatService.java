package io.github.oatelauser.jauth.selfservice.pat;

import io.github.oatelauser.jauth.core.response.JauthErrorCode;
import io.github.oatelauser.jauth.core.response.JauthException;
import io.github.oatelauser.jauth.core.util.UuidV7;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.util.Assert;

/**
 * PAT 存储内存实现（契约与 {@link JdbcPatService} 一致）。
 *
 * <p><b>生产装配不注册本类</b>（04 票：memory 模式禁用 PAT）——存在意义是让契约测试与无库环境（宿主内嵌联调、
 * 工具脚本）能跑全套生命周期；哈希纪律照旧执行（明文不进 Map，键是哈希）。
 *
 * <p>并发模型：方法级 synchronized。PAT 操作频率是"人手点页面"量级，简单锁吞吐富余（与 core
 * InMemoryTokenFamilyService 的取舍一致）。
 *
 * @author oatelauser
 */
public class InMemoryPatService implements PatService {

    private final Map<String, PatRecord> recordsById = new ConcurrentHashMap<>();

    private final Clock clock;

    public InMemoryPatService(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized PatIssuance create(String userId, String name, Set<String> scopes, Duration validity) {
        Assert.hasText(userId, "userId cannot be empty");
        Assert.hasText(name, "name cannot be empty");
        Assert.notEmpty(scopes, "scopes cannot be empty");
        Assert.notNull(validity, "validity cannot be null");
        String rawToken = PatTokens.generate();
        Instant now = this.clock.instant();
        PatRecord record = new PatRecord(
                UuidV7.generate().toString(),
                userId,
                name.trim(),
                PatTokens.displayPrefix(rawToken),
                scopes,
                PatStatus.ACTIVE,
                now,
                now.plus(validity),
                null);
        this.recordsById.put(record.id(), record);
        return new PatIssuance(record, rawToken);
    }

    @Override
    public synchronized List<PatRecord> listActive(String userId) {
        Assert.hasText(userId, "userId cannot be empty");
        List<PatRecord> active = new ArrayList<>();
        for (PatRecord record : this.recordsById.values()) {
            if (record.userId().equals(userId) && record.status() == PatStatus.ACTIVE) {
                active.add(record);
            }
        }
        active.sort(Comparator.comparing(PatRecord::createdAt).reversed().thenComparing(PatRecord::id));
        return List.copyOf(active);
    }

    @Override
    public synchronized void revoke(String userId, String patId) {
        Assert.hasText(userId, "userId cannot be empty");
        Assert.hasText(patId, "patId cannot be empty");
        PatRecord record = this.recordsById.get(patId);
        if (record == null || !record.userId().equals(userId) || record.status() != PatStatus.ACTIVE) {
            // 与 JDBC 版同义：不存在、非本人、已吊销皆 B0502
            throw new JauthException(JauthErrorCode.B0502);
        }
        this.recordsById.put(patId, revoked(record));
    }

    private static PatRecord revoked(PatRecord record) {
        return new PatRecord(
                record.id(),
                record.userId(),
                record.name(),
                record.tokenPrefix(),
                record.scopes(),
                PatStatus.REVOKED,
                record.createdAt(),
                record.expiresAt(),
                record.lastUsedAt());
    }
}
