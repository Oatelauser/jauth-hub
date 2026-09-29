package io.github.oatelauser.jauth.app.web;

import io.github.oatelauser.springplus.security.annotation.Principal;
import io.github.oatelauser.springplus.web.response.SimpleResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /demo 教学区的受保护示例接口（SPEC §7 最后一步：持 token 调受保护接口闭环）。同 app 内配置 rs-starter
 * 指向自身内省端点（AppSecurityConfiguration），opaque token 经内省换取主体——响应内容即"资源服务器视角
 * 从内省学到什么"。
 *
 * @author oatelauser
 */
@RestController
public class DemoWhoamiController {

    @GetMapping("/api/demo/whoami")
    public SimpleResponse<Map<String, Object>> whoami(@Principal OAuth2AuthenticatedPrincipal principal) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sub", principal.getAttribute("sub"));
        body.put("username", principal.getAttribute("username"));
        body.put("scope", principal.getAttribute("scope"));
        body.put(
                "authorities",
                principal.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .toList());
        return SimpleResponse.ok(body);
    }
}
