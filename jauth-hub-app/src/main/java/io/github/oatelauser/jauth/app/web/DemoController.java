package io.github.oatelauser.jauth.app.web;

import io.github.oatelauser.jauth.resourceserver.JauthResourceServerProperties;
import io.github.oatelauser.jauth.starter.JauthHubProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * /demo 教学区四页路由（SPEC §7：真实联调教学区）：v1.5 B5b 起 GET 无条件 302 到
 * {@code /front/demo*} 的 SPA 皮（查询串原样转发——callback 页的 code/state 依赖它）；页面配置装配
 * {@link #demoConfig} 是 SSR 四页与 JSON 状态面（{@link DemoConfigStateController}）的共用单点，
 * 不复制。客户端标识是与 application.yml 播种清单绑定的教学夹具常量（demo-public 公开 PKCE 客户端、
 * demo-rs 内省用机密客户端），真源在 yml 播种配置。
 *
 * @author oatelauser
 */
@Controller
public class DemoController {

    /** 教学区公开客户端（application.yml jauth-hub.clients[0] 同步维护）。 */
    static final String DEMO_PUBLIC_CLIENT_ID = "demo-public";

    /** 教学区内省演示用机密客户端（yml clients[1] 与 jauth-hub.rs.* 同步维护）。 */
    static final String DEMO_RS_CLIENT_ID = "demo-rs";

    /** 教学区申请的 scope（与种子客户端注册一致）。 */
    static final String DEMO_SCOPE = "openid profile";

    private final String issuer;

    private final AuthorizationServerSettings authorizationServerSettings;

    private final String rsClientSecret;

    public DemoController(
            JauthHubProperties hubProperties,
            AuthorizationServerSettings authorizationServerSettings,
            JauthResourceServerProperties resourceServerProperties) {
        // 构造期快照字符串值（SpotBugs EI_EXPOSE_REP2：@ConfigurationProperties 对象可变，不落字段）
        this.issuer = hubProperties.getIssuer();
        this.authorizationServerSettings = authorizationServerSettings;
        this.rsClientSecret = resourceServerProperties.getClientSecret();
    }

    /** 教学区首页入口：302 到 SPA 皮。 */
    @GetMapping("/demo")
    public String index(HttpServletRequest request) {
        return "redirect:" + RootController.frontTarget("/front/demo", request);
    }

    /** 教学区回调页入口：302 到 SPA 皮（code/state 查询串原样转发——回调页依赖）。 */
    @GetMapping("/demo/callback")
    public String callback(HttpServletRequest request) {
        return "redirect:" + RootController.frontTarget("/front/demo/callback", request);
    }

    /** 教学区令牌页入口：302 到 SPA 皮。 */
    @GetMapping("/demo/token")
    public String token(HttpServletRequest request) {
        return "redirect:" + RootController.frontTarget("/front/demo/token", request);
    }

    /** 教学区 API 调用页入口：302 到 SPA 皮。 */
    @GetMapping("/demo/api-call")
    public String apiCall(HttpServletRequest request) {
        return "redirect:" + RootController.frontTarget("/front/demo/api-call", request);
    }

    /**
     * 页面 JS 配置装配（v1.5 B1c 提取包内 static）：SSR 四页（历史）与 JSON 状态面
     * （{@link DemoConfigStateController}）共用单点，不复制。全部端点由 AuthorizationServerSettings
     * 实际值拼出（改 issuer/路径配置不用动页面）。
     */
    static Map<String, Object> demoConfig(
            String issuer, AuthorizationServerSettings authorizationServerSettings, String rsClientSecret) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("issuer", issuer);
        config.put("authorizeEndpoint", issuer + authorizationServerSettings.getAuthorizationEndpoint());
        config.put("tokenEndpoint", issuer + authorizationServerSettings.getTokenEndpoint());
        config.put("introspectEndpoint", issuer + authorizationServerSettings.getTokenIntrospectionEndpoint());
        config.put("clientId", DEMO_PUBLIC_CLIENT_ID);
        // v1.5 B4：回调指向 SPA（/front/demo/callback）；旧 SSR 回调路径由种子白名单旧条目继续兼容
        config.put("redirectUri", issuer + "/front/demo/callback");
        config.put("scope", DEMO_SCOPE);
        // 内省演示凭证与 rs-starter 同源（jauth-hub.rs.*）：教学页直连 /introspect 复用同一机密客户端。
        // 仅 dev 教学夹具可如此——生产内省凭证只存在于资源服务器后端，页面教学文案明示这一点。
        config.put("rsClientId", DEMO_RS_CLIENT_ID);
        config.put("rsClientSecret", rsClientSecret);
        config.put("whoamiUri", issuer + "/api/demo/whoami");
        return config;
    }
}
