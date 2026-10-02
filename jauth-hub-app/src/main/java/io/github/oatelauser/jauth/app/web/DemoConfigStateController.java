package io.github.oatelauser.jauth.app.web;

import io.github.oatelauser.jauth.core.response.ResponseRenderer;
import io.github.oatelauser.jauth.core.web.EducationalFlag;
import io.github.oatelauser.jauth.resourceserver.JauthResourceServerProperties;
import io.github.oatelauser.jauth.starter.JauthHubProperties;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /demo 教学区 demoConfig JSON 状态面（v1.5 B1c，四页共用一端点）：{@code GET /api/demo/config} 与 SSR
 * 四页（{@link DemoController}）同口径——装配复用 {@link DemoController#demoConfig} 提取的包内
 * statics（不复制）。组件扫描注册（app 模块 @Controller 同形态）。
 *
 * <p><b>无 csrf 对——契约家族的有意例外</b>：demo 是公开教学页（登录前的 authorize 流程起点），页内操作
 * 全走 OAuth2 协议端点（authorize 顶层导航、token/introspect 携带客户端凭证的 fetch），不经 CsrfFilter
 * 保护的 jauth JSON 面，故状态体不带 csrfToken/csrfHeaderName。端点本身在壳层 default 链随 {@code /demo}
 * 教学区匿名放行（AppSecurityConfiguration），与 SSR 页 ${demoConfig} 内联的既有可达面一致。
 *
 * <p><b>rsClientSecret 边界照旧</b>（survey §3.3 / DemoController 同款口径）：它是与 rs-starter 同源的
 * dev 教学夹具（jauth-hub.rs.*），SSR 页早已把同值内联进公开 HTML——JSON 化只是同一受众换一载体，不扩大
 * 暴露；生产内省凭证只存在于资源服务器后端，教学文案明示这一点。
 *
 * <p>B3 前端消费契约：{@code data:{ educational, demoConfig }}；demoConfig 十字段
 * （issuer/authorizeEndpoint/tokenEndpoint/introspectEndpoint/clientId/redirectUri/scope/rsClientId/
 * rsClientSecret/whoamiUri）与 SSR 页 CFG 同源同值。
 *
 * @author oatelauser
 */
@RestController
public class DemoConfigStateController {

    private final String issuer;

    private final AuthorizationServerSettings authorizationServerSettings;

    private final String rsClientSecret;

    private final EducationalFlag educational;

    private final ResponseRenderer responseRenderer;

    public DemoConfigStateController(
            JauthHubProperties hubProperties,
            AuthorizationServerSettings authorizationServerSettings,
            JauthResourceServerProperties resourceServerProperties,
            EducationalFlag educational,
            ResponseRenderer responseRenderer) {
        // 构造期快照字符串值（SpotBugs EI_EXPOSE_REP2：@ConfigurationProperties 对象可变，不落字段——
        // DemoController 同款取舍）
        this.issuer = hubProperties.getIssuer();
        this.authorizationServerSettings = authorizationServerSettings;
        this.rsClientSecret = resourceServerProperties.getClientSecret();
        this.educational = educational;
        this.responseRenderer = responseRenderer;
    }

    /** 教学区配置状态（四页共用，无请求态依赖）。 */
    @GetMapping(value = "/api/demo/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object state() {
        return this.responseRenderer.renderSuccess(new DemoConfigState(
                this.educational.enabled(),
                DemoController.demoConfig(this.issuer, this.authorizationServerSettings, this.rsClientSecret)));
    }

    /** 教学区配置状态载荷（字段名即 B3 前端契约；demoConfig 与 SSR 页 CFG 同源同值）。 */
    public record DemoConfigState(boolean educational, Map<String, Object> demoConfig) {

        /** demoConfig 防御性拷贝（SpotBugs EI_EXPOSE_REP：出入均不可变——Map.copyOf 同 List.copyOf 家族先例；键序在 JSON 面无语义）。 */
        public DemoConfigState {
            demoConfig = demoConfig == null ? Map.of() : Map.copyOf(demoConfig);
        }
    }
}
