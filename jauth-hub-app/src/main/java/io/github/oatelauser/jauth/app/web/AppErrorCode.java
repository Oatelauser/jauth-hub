package io.github.oatelauser.jauth.app.web;

import io.github.oatelauser.jauth.core.response.ErrorCode;

/**
 * 壳层用户域错误码（A05xx 段内，B12 增补；段位账见 core {@code JauthErrorCode} javadoc）。
 *
 * <p>落在 app 而非 core 的 {@code JauthErrorCode}：用户管理页/自助改密是 app 专属面（SPEC §2），照
 * SelfServiceErrorCode 的既定形态在需求方模块自立。
 *
 * @author oatelauser
 */
public enum AppErrorCode implements ErrorCode {

    /** 建号撞名：用户名已被占用（含并发撞唯一约束的翻译，B12）。 */
    A0512("A0512", "用户名已存在"),

    /** 管理员不可对自己执行该操作（改角色/停用启用——防超管自降/自停锁死，B12）。 */
    A0513("A0513", "不可对自己执行该操作"),

    /** 自助改密的旧密码错误，或失败次数已达登录锁定阈值（B12）。 */
    A0514("A0514", "旧密码错误或尝试次数过多");

    private final String code;

    private final String message;

    AppErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String getCode() {
        return this.code;
    }

    @Override
    public String getMessage() {
        return this.message;
    }
}
