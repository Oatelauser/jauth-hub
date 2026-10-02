package io.github.oatelauser.jauth.core.web;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.security.web.csrf.CsrfToken;

/**
 * JSON 状态面的 CSRF 字段对（v1.4 B1）：取自 CsrfFilter 惰性请求属性（Spring Security 6+ 默认
 * XorCsrfTokenRequestAttributeHandler，请求属性名 {@link CsrfToken#getClass()} 的全限定名）——
 * {@code csrfToken} 为 token 原值（与 SSR 页 {@code ${_csrf.token}} 同一来源），{@code csrfHeaderName}
 * 为 {@link CsrfToken#getHeaderName()}（Spring Security 默认 {@code X-CSRF-TOKEN}，HTTP 头名大小写
 * 不敏感）。headless 皮照此回传即可通过 CsrfFilter 校验（starter 真链探针测试钉死该契约）。
 *
 * <p>属性缺席（所在链未启用 Spring Security CSRF，如部分宿主 default 链上的自助页）两字段为 null——
 * 与 SSR 模板 {@code th:if="${_csrf != null}"} 的缺省语义一致，消费端按可空处理。
 *
 * @author oatelauser
 */
public record CsrfPayload(@Nullable String csrfToken, @Nullable String csrfHeaderName) {

    /** 从当前请求提取 CSRF 字段对（无 CsrfFilter 面时两字段 null）。 */
    public static CsrfPayload from(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return token == null ? new CsrfPayload(null, null) : new CsrfPayload(token.getToken(), token.getHeaderName());
    }
}
