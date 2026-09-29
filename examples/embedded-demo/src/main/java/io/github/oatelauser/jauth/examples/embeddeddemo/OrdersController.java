package io.github.oatelauser.jauth.examples.embeddeddemo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 宿主受保护业务接口：经 rs-starter 指向自身的内省端点验证 opaque token（06 Q1 模式闭环演示——"宿主接口
 * 自己接 OAuth 保护"）。主体即内省结果（sub/username/scope），业务侧可直接用 scope 做细粒度判断。
 *
 * <p>本示例未引 spring-plus 三件套（它们是 app 模块的可选增强，不是嵌入必选），原生
 * {@code @AuthenticationPrincipal} 注入即可完成演示。
 *
 * @author oatelauser
 */
@RestController
public class OrdersController {

    @GetMapping("/api/orders")
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
