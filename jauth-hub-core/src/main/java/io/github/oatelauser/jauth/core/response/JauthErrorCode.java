package io.github.oatelauser.jauth.core.response;

/**
 * jauth-hub 错误码基座：集中常量，禁止散落魔法串（p3c 常量章）。
 *
 * <p>占段 A05xx（授权/客户端请求错）+ B05xx（认证中心内部错）——spring-plus 家族分段惯例，与家族已占段（如 A0301）不撞车（09 票决议）。
 * 按批增补不预铺：A0501-A0502/B05xx 为基座四枚；selfservice 已占 A0503-A0505（登录主体/存储模式/scope 勾选）、
 * A0509-A0510（B10 应用注册名称/redirect URI 校验）与 A0511（B11 安装审批 ceiling 空勾选）；B8 增 A0506-A0508（org/安装域）；
 * app 已占 A0512-A0514（B12 用户管理：撞名/自操作拒/旧密码错，落 app 自立枚举 AppErrorCode）。
 *
 * @author oatelauser
 */
public enum JauthErrorCode implements ErrorCode {

    /** 请求缺少必要参数。 */
    A0501("A0501", "参数缺失"),

    /** 请求参数格式或取值非法。 */
    A0502("A0502", "参数非法"),

    /** 认证中心内部错误。 */
    B0501("B0501", "内部错误"),

    /** 请求的数据不存在。 */
    B0502("B0502", "数据不存在"),

    /** 唯一性冲突：org 重名，或 (client, org) 已有 PENDING/APPROVED 安装（B8）。 */
    A0506("A0506", "名称或唯一键冲突"),

    /** 安装状态机不允许该操作：如对非 PENDING 行审批/驳回、对非 APPROVED 行撤销（B8）。 */
    A0507("A0507", "当前状态不允许该操作"),

    /** 无权限执行该操作：如非 org OWNER 试图审批/驳回/撤销安装（B8）；组织客户端发行无可用 org 上下文——未安装/安装非 APPROVED，或多 org 未选择（B9 fail-closed）。 */
    A0508("A0508", "无权限执行该操作");

    private final String code;

    private final String message;

    JauthErrorCode(String code, String message) {
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
