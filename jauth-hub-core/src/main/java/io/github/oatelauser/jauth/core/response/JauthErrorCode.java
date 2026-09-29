package io.github.oatelauser.jauth.core.response;

/**
 * jauth-hub 错误码基座：集中常量，禁止散落魔法串（p3c 常量章）。
 *
 * <p>占段 A05xx（授权/客户端请求错）+ B05xx（认证中心内部错）——spring-plus 家族分段惯例，与家族已占段（如 A0301）不撞车（09 票决议）。
 * 本批只预置最常用四枚，后续批次按需增补，不预铺。
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
    B0502("B0502", "数据不存在");

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
