package io.github.oatelauser.jauth.core.audit;

/**
 * 审计事件发布 SPI（票 07：追加只写）。
 *
 * <p>双实现：{@link JdbcAuditEventService}（jdbc 模式，落 jauth_audit_event 表）与
 * {@link InMemoryRollingAuditService}（memory 模式降级，仅调试语义）。事件源（登录监听、授权服务
 * save/remove 装饰、consent 装饰）只依赖本接口。
 *
 * <p>实现约定：发布不抛——审计是旁路观测，落库失败不得反噬主流程（内存实现无此问题，JDBC 实现捕获
 * 记 WARN）；IP/UA 由此处的实现从请求上下文补齐（事件字段为 null 时）。
 *
 * @author oatelauser
 */
public interface AuditEventPublisher {

    /**
     * 发布一件审计事件（旁路语义，见接口注释）。
     *
     * @param event 事件（IP/UA 留空时由实现自请求上下文补齐）
     */
    void publish(AuditEvent event);
}
