package io.github.oatelauser.jauth.core.response;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 默认渲染器：输出 {@code {code, message, data}} 三段裸 JSON——字段名照抄 spring-plus SimpleResponse 契约， 成功码
 * 00000。独立模式切真 SimpleResponse 类型时前端 JSON 零变化（09 票）；正式衔接（app 直接用 SimpleResponse）归 B5/app 装配。
 *
 * @author oatelauser
 */
public class DefaultResponseRenderer implements ResponseRenderer {

    /** 家族分段惯例：00000 是唯一成功码。 */
    static final String SUCCESS_CODE = "00000";

    static final String SUCCESS_MESSAGE = "success";

    @Override
    public Object renderSuccess(Object data) {
        Map<String, Object> body = new LinkedHashMap<>(4);
        body.put("code", SUCCESS_CODE);
        body.put("message", SUCCESS_MESSAGE);
        body.put("data", data);
        return body;
    }

    @Override
    public Object renderFail(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>(4);
        body.put("code", code);
        body.put("message", message);
        // Map.of 不容 null，失败体 data 置 null 走 HashMap 族容器
        body.put("data", null);
        return body;
    }
}
