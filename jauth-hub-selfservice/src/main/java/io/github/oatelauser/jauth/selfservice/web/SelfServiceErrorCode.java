package io.github.oatelauser.jauth.selfservice.web;

import io.github.oatelauser.jauth.core.response.ErrorCode;

/**
 * 自助模块错误码（A05xx 段内，09 票占段约定的本批增补）。
 *
 * <p>落在 selfservice 而非 core 的 {@code JauthErrorCode}：core 枚举"后续批次按需增补"的增补点在需求方，
 * 本批模块边界只开放 core 的 ClientSeeder 一处（B5a 批约定），故自助专用码在此自立。
 *
 * @author oatelauser
 */
public enum SelfServiceErrorCode implements ErrorCode {

    /** 登录主体不可用：未认证，或主体不在 jauth 用户池（嵌入宿主自有用户访问自助页）。 */
    A0503("A0503", "登录主体不可用（未认证或不在用户池）"),

    /** 当前存储模式不支持该自助操作（memory 模式禁用 PAT 与授权看板查询，04 票）。 */
    A0504("A0504", "当前存储模式不支持该自助操作"),

    /** 创建 PAT 的 scope 勾选为空或含目录外 scope。 */
    A0505("A0505", "scope 勾选为空或不在目录内");

    private final String code;

    private final String message;

    SelfServiceErrorCode(String code, String message) {
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
