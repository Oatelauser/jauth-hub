package io.github.oatelauser.jauth.core.audit;

import io.github.oatelauser.jauth.core.util.UuidV7;
import java.sql.Timestamp;
import java.time.Clock;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.util.Assert;

/**
 * 审计事件 JDBC 实现（表 jauth_audit_event，V2 建表）：追加只写。
 *
 * <p><b>发布不抛</b>（SPI 约定）：DataAccessException 捕获记 WARN——审计是旁路观测，落库故障不得反噬
 * 令牌签发/登录等主流程。IP/UA 自请求上下文补齐（无上下文留 null，即留空列）。
 *
 * <p>构造不触表（无表元数据读取），首事件到达时表必已在（starter Flyway 先行，与 selfservice 仓储
 * 同一时序论证）。
 *
 * @author oatelauser
 */
public class JdbcAuditEventService implements AuditEventPublisher {

    private static final Log LOG = LogFactory.getLog(JdbcAuditEventService.class);

    private static final String INSERT_EVENT =
            "INSERT INTO jauth_audit_event (id, ts, event_type, actor_user_id, target_type,"
                    + " target_id, ip, user_agent, detail) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcOperations jdbcOperations;

    private final Clock clock;

    public JdbcAuditEventService(JdbcOperations jdbcOperations, Clock clock) {
        Assert.notNull(jdbcOperations, "jdbcOperations cannot be null");
        Assert.notNull(clock, "clock cannot be null");
        this.jdbcOperations = jdbcOperations;
        this.clock = clock;
    }

    @Override
    public void publish(AuditEvent event) {
        Assert.notNull(event, "event cannot be null");
        try {
            this.jdbcOperations.update(
                    INSERT_EVENT,
                    UuidV7.generate().toString(),
                    Timestamp.from(this.clock.instant()),
                    event.type().wireName(),
                    event.actorUserId(),
                    event.targetType(),
                    event.targetId(),
                    event.ip() != null ? event.ip() : RequestAuditContext.currentIp(),
                    event.userAgent() != null ? event.userAgent() : RequestAuditContext.currentUserAgent(),
                    event.detail());
        } catch (DataAccessException ex) {
            // SPI 约定：旁路观测不反噬主流程（类注释）
            LOG.warn("Audit event persist failed: " + event.type().wireName(), ex);
        }
    }
}
