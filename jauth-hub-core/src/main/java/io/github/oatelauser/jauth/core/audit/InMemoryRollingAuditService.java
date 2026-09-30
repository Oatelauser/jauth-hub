package io.github.oatelauser.jauth.core.audit;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.springframework.util.Assert;

/**
 * 审计事件内存滚动缓冲（memory 模式降级实现，SPEC §3：审计降级内存滚动缓冲，标注仅调试）。
 *
 * <p><b>memory 模式语义</b>：有界双端队列（默认容量 200），满即丢最旧——不承诺完整性、重启即失，
 * 面向 demo/内嵌联调的事件观测；生产审计走 {@link JdbcAuditEventService}（jauth-hub.storage=jdbc）。
 * IP/UA 补齐逻辑与 JDBC 版一致（无请求上下文留 null）。
 *
 * <p>并发模型：方法级 synchronized（与 core InMemoryTokenFamilyService 取舍一致）。
 *
 * @author oatelauser
 */
public class InMemoryRollingAuditService implements AuditEventPublisher {

    /** 默认滚动容量：足够覆盖一次联调会话的生命周期事件，不伪装成持久审计。 */
    public static final int DEFAULT_CAPACITY = 200;

    private final int capacity;

    private final Clock clock;

    private final Deque<TimestampedEvent> buffer = new ArrayDeque<>();

    public InMemoryRollingAuditService(Clock clock) {
        this(DEFAULT_CAPACITY, clock);
    }

    public InMemoryRollingAuditService(int capacity, Clock clock) {
        Assert.isTrue(capacity > 0, "capacity must be positive");
        Assert.notNull(clock, "clock cannot be null");
        this.capacity = capacity;
        this.clock = clock;
    }

    @Override
    public synchronized void publish(AuditEvent event) {
        Assert.notNull(event, "event cannot be null");
        if (buffer.size() == capacity) {
            buffer.removeFirst();
        }
        buffer.addLast(new TimestampedEvent(
                this.clock.instant(),
                event.type(),
                event.actorUserId(),
                event.targetType(),
                event.targetId(),
                event.ip() != null ? event.ip() : RequestAuditContext.currentIp(),
                event.userAgent() != null ? event.userAgent() : RequestAuditContext.currentUserAgent(),
                event.detail()));
    }

    /**
     * 当前缓冲快照（旧→新）。
     *
     * @return 不可变快照列表
     */
    public synchronized List<TimestampedEvent> snapshot() {
        return List.copyOf(new ArrayList<>(buffer));
    }

    /** 带时间戳的缓冲行（memory 模式的事件观测形状）。 */
    public record TimestampedEvent(
            Instant instant,
            AuditEventType type,
            String actorUserId,
            String targetType,
            String targetId,
            String ip,
            String userAgent,
            String detail) {}
}
