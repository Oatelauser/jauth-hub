package io.github.oatelauser.jauth.examples.embeddeddemo;

import io.github.oatelauser.jauth.core.web.RequiresScope;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 宿主受保护业务接口：经 rs-starter 指向自身的内省端点验证 opaque token（06 Q1 模式闭环演示——"宿主接口
 * 自己接 OAuth 保护"）。主体即内省结果（sub/username/scope）。
 *
 * <p>{@code @RequiresScope}（v1.2 C4 三合一演示）：一次标注同时获得——启动自动把 {@code orders:read}
 * 注册进 scope 目录（consent 页可见、PAT/安装勾选面不再 A0505 拒）；请求期拦截器校验当前令牌
 * authorities（{@code orders:read} 原串或 {@code SCOPE_orders:read} 前缀都认，缺则 403）；
 * desc "读取订单" 作为 consent 页 i18n 未命中的兜底文案。冒号格式与 spring-plus 权限键对齐。
 *
 * <p>本示例未引 spring-plus 三件套（它们是 app 模块的可选增强，不是嵌入必选），原生
 * {@code @AuthenticationPrincipal} 注入即可完成演示。
 *
 * @author oatelauser
 */
@RestController
public class OrdersController {

    @GetMapping("/api/orders")
    @RequiresScope(value = "orders:read", desc = "读取订单")
    public Map<String, Object> orders(@AuthenticationPrincipal OAuth2AuthenticatedPrincipal principal) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("id", "order-1001");
        order.put("item", "jauth-hub embedded demo order");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("for", principal.getAttribute("username"));
        body.put("orders", List.of(order));
        return body;
    }
}
