package io.github.oatelauser.jauth.app.web;

import io.github.oatelauser.springplus.security.annotation.Principal;
import io.github.oatelauser.springplus.security.annotation.RequiresRole;
import io.github.oatelauser.springplus.web.response.SimpleResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * spring-plus 声明式鉴权的接线样例（08 票落点：app 管理接口走 {@code @Requires*}）：SUPER_ADMIN 专属端点，
 * 角色来自 {@code AppUserDetailsService} 的 ROLE_SUPER_ADMIN 映射（jauth_user.role=SUPERADMIN），家族超管
 * 短路即生效——已认证但非超管 403，未认证 401。用户管理页 v1.1 落地时沿用此模式。
 *
 * @author oatelauser
 */
@RestController
public class AdminSampleController {

    @RequiresRole(role = RequiresRole.ROLE_SUPER_ADMIN)
    @GetMapping("/api/admin/summary")
    public SimpleResponse<Map<String, Object>> summary(@Principal UserDetails admin) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operator", admin.getUsername());
        body.put("userStore", "jauth_user（自持用户库）");
        body.put("storage", "jdbc");
        return SimpleResponse.ok(body);
    }
}
