package io.github.oatelauser.jauth.selfservice.web;

import io.github.oatelauser.jauth.core.response.ErrorCode;

/**
 * 自助模块错误码（A05xx 段内，09 票占段约定按批增补；段位账见 core {@code JauthErrorCode} javadoc）。
 *
 * <p>落在 selfservice 而非 core 的 {@code JauthErrorCode}：core 枚举"后续批次按需增补"的增补点在需求方，
 * 自助专用码在需求方模块自立（B5a 起的既定形态；core 侧 A0506-A0508 归 B8 org/安装域）。
 *
 * @author oatelauser
 */
public enum SelfServiceErrorCode implements ErrorCode {

    /** 登录主体不可用：未认证，或主体不在 jauth 用户池（嵌入宿主自有用户访问自助页）。 */
    A0503("A0503", "登录主体不可用（未认证或不在用户池）"),

    /** 当前存储模式不支持该自助操作（memory 模式禁用 PAT 与授权看板查询，04 票）。 */
    A0504("A0504", "当前存储模式不支持该自助操作"),

    /** scope 勾选为空或含目录外 scope（PAT 创建与安装发起共用语义，B11 起双面）。 */
    A0505("A0505", "scope 勾选为空或不在目录内"),

    /** 应用注册的名称缺失或非法（空或超 100 字符，B10）。 */
    A0509("A0509", "应用名缺失或非法"),

    /** 应用注册的 redirect URIs 缺失或非法（须为 http/https 精确 URL，B10）。 */
    A0510("A0510", "redirect URI 缺失或非法"),

    /** 安装审批的 ceiling 勾选为空（批准即封顶，空集无意义；驳回走专用动作，B11）。 */
    A0511("A0511", "安装审批的 ceiling 勾选为空"),

    /** 需要强验证（sudo）：敏感操作前最近一次 passkey 强认证已过期或缺失，先去 /selfservice/sudo 验证（v1.2 C3）。 */
    A0515("A0515", "需要强验证（sudo）");

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
