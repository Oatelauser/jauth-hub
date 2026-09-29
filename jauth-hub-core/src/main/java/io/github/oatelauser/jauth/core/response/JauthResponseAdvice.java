package io.github.oatelauser.jauth.core.response;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * jauth 自有 advice：只接 {@link JauthException}，委托 {@link ResponseRenderer} 渲染失败体。
 *
 * <p>边界（09 票核实）：协议端点（/authorize /token /introspect…）的异常在过滤器层消化成 RFC error 响应，根本走不到 MVC
 * advice——本类天然只覆盖非协议端点，故无需路径过滤；也不依赖宿主全局 advice（防其误包装协议端点）。
 *
 * <p>序位取接近最高优先：宿主若配了接 RuntimeException 的兜底 advice，须让本类先命中精确类型（数值小者先）。
 *
 * @author oatelauser
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class JauthResponseAdvice {

    private final ResponseRenderer renderer;

    public JauthResponseAdvice(ResponseRenderer renderer) {
        this.renderer = renderer;
    }

    /**
     * 业务失败渲染：HTTP 状态保持 200，语义在 code 段（00000/A05xx/B05xx 家族惯例）。
     *
     * @param exception 业务异常
     * @return SPI 渲染的失败体
     */
    @ExceptionHandler(JauthException.class)
    public Object handle(JauthException exception) {
        return renderer.renderFail(exception.getCode(), exception.getMessage());
    }
}
