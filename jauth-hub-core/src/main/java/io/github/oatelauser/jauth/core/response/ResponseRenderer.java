package io.github.oatelauser.jauth.core.response;

/**
 * 统一响应渲染 SPI：jauth-hub 不写死响应结构，宿主注册自己的实现即映射到自家统一对象（R&lt;T&gt;/SimpleResponse/Result&lt;T&gt;…）。
 *
 * <p>只覆盖非协议端点（管理/自助/demo）；协议端点是 RFC 标准 error 格式、平台 API /me 是裸 JSON，均不经此 SPI（SPEC §4 端点三分）。
 *
 * @author oatelauser
 */
public interface ResponseRenderer {

    /**
     * 成功包装。
     *
     * @param data 业务数据（分页走 data 携带通用分页 DTO，字段照抄家族 PageResponse 线上契约）
     * @return 宿主自己的响应类型
     */
    Object renderSuccess(Object data);

    /**
     * 失败包装。
     *
     * @param code 错误码（A05xx/B05xx 段）
     * @param message 人读消息
     * @return 宿主自己的响应类型
     */
    Object renderFail(String code, String message);
}
