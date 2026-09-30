package io.github.oatelauser.jauth.core.audit;

import org.jspecify.annotations.Nullable;

/**
 * 审计事件（表 jauth_audit_event 的域投影，V2 建表）。
 *
 * <p><b>只记生命周期</b>（票 07/P5：签发/刷新/撤销/consent/登录/审批）；内省不打审计。detail 为
 * 自由文本（表列 TEXT，双兼容纪律不用 jsonb），IP/UA 由发布实现从请求上下文补齐——事件构造方不感知
 * Web 层；无请求上下文的事件（后台播种、测试直调）两字段留 null。
 *
 * @param type 事件类型（词表 {@link AuditEventType}）
 * @param actorUserId 行为主体（jauth_user.id），系统/未知为 null
 * @param targetType 目标对象类型（client/token/consent/pat…），无目标为 null
 * @param targetId 目标对象标识
 * @param ip 请求来源地址（无请求上下文为 null）
 * @param userAgent 请求 User-Agent（无请求上下文为 null）
 * @param detail 补充细节（TEXT）
 * @author oatelauser
 */
public record AuditEvent(
        AuditEventType type,
        @Nullable String actorUserId,
        @Nullable String targetType,
        @Nullable String targetId,
        @Nullable String ip,
        @Nullable String userAgent,
        @Nullable String detail) {

    /** 便捷工厂：省略 IP/UA（发布实现自请求上下文补齐）与可选字段。 */
    public static AuditEvent of(
            AuditEventType type,
            @Nullable String actorUserId,
            @Nullable String targetType,
            @Nullable String targetId,
            @Nullable String detail) {
        return new AuditEvent(type, actorUserId, targetType, targetId, null, null, detail);
    }
}
