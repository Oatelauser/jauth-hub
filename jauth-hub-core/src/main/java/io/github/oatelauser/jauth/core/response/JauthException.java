package io.github.oatelauser.jauth.core.response;

import java.io.Serial;

/**
 * 非协议端点的业务异常：携带错误码 + 人读消息，由 {@link JauthResponseAdvice} 捕获并经 {@link ResponseRenderer} 渲染。
 *
 * <p>协议端点（/token /introspect 等）的错误走 RFC error/error_description，在过滤器层消化，永远不会以本异常冒泡（09 票边界）。
 *
 * @author oatelauser
 */
public class JauthException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String code;

    public JauthException(String code, String message) {
        super(message);
        this.code = code;
    }

    public JauthException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public String getCode() {
        return this.code;
    }
}
