package io.github.oatelauser.jauth.core.response;

/**
 * 错误码最小契约：code 分段语义见 {@link JauthErrorCode}（A05xx 客户端 / B05xx 内部，SPEC §1）。
 *
 * @author oatelauser
 */
public interface ErrorCode {

    String getCode();

    String getMessage();
}
