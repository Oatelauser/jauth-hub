package io.github.oatelauser.jauth.core.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 信任面皮肤开关 Provider（v1.4 B4，照 {@link PasskeyFlag} 先例）：front 开启时四页 SSR 控制器的 GET 入口
 * 不渲染视图，302 到 {@code /front/<路由>} 的 SPA 皮（jauth-hub-front 构建产物，认证中心同域名部署）。
 *
 * <p>宪法（SPEC §1、issues/10）：<b>SSR 皮永远默认，front 只是备选皮部署形态</b>——{@code jauth-hub.trust-skin=front}
 * 显式开启（starter 属性装配落地），core 只提供此接口与默认常量；302 而非 forward 保 URL 语义与 SPA 路由
 * base 一致，查询串（?error、client_id/state/scope、returnTo）原样转发给 SPA 消费。
 *
 * @author oatelauser
 */
public interface TrustSkinFlag {

    /** 默认实现：SSR 皮（与 jauth-hub.trust-skin 缺省 ssr 一致）。 */
    TrustSkinFlag SSR = () -> false;

    /**
     * front 分离皮肤是否开启。
     *
     * @return true 表示四页 GET 302 到 /front/&lt;路由&gt;
     */
    boolean frontEnabled();

    /**
     * 302 目标构建（四页 SSR 控制器共用）：frontPath + <b>原样保留</b>的查询串。
     *
     * <p>必须走 {@link HttpServletRequest#getQueryString()} 而非重组参数——框架重定向带来的
     * client_id/state/scope 等参数的拼接顺序与编码（空格、多值）逐字透传给 SPA，空查询串不加
     * 尾缀 {@code ?}。
     *
     * @param frontPath SPA 路由路径（如 /front/login）
     * @param request 当前请求
     * @return 重定向目标（路径或路径?查询串）
     */
    static String frontTarget(String frontPath, HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null || query.isEmpty() ? frontPath : frontPath + "?" + query;
    }
}
