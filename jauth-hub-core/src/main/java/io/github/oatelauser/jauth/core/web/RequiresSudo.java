package io.github.oatelauser.jauth.core.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 敏感操作强验证标记（sudo，v1.2 C3，与 {@link PasskeyFlag} 同域）：方法标注即声明"该操作要求最近一次
 * passkey 强认证仍在 TTL 内"，由 selfservice 的拦截器统一把门（{@code SudoInterceptor}），业务方法零侵入。
 *
 * <p>语义同 GitHub sudo：TTL 内免重验，过期首次触达被拦（A0515 JSON，不做 302 分叉——敏感端点全是页面
 * fetch 调的 JSON API）→ 验证页 passkey 就地升权 → 回原表单页<b>用户重填重交</b>（POST 不重放，决议的一部分）。
 *
 * <p>仅方法级：敏感面是逐端点的白名单决策（建号/显示名不属敏感面不标），类级放大与决议不符。
 *
 * @author oatelauser
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresSudo {}
