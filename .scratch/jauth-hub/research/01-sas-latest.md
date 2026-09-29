# 01 Spring Authorization Server 最新 API 与功能面调研

调研日期：2026-09-25。工具：Context7（Spring Security 7.0 reference）+ GitHub MCP（releases / code search / issues）+ 官方博客。

## 结论摘要

1. **SAS 作为独立项目已终结**。2025-09-11 官宣并入 Spring Security 7.0；原仓库已归档至 `spring-attic/spring-authorization-server`（archived=true），**1.5.x 是最后一代独立版本**，最新补丁 1.5.8（2026-06-09），基线 Spring Security 6.5.11 / Spring Framework 6.2.19（对应 Spring Boot 3.5.x 线）。
2. **当前形态**：Spring Security 仓库内的模块 `oauth2/oauth2-authorization-server`，坐标 `org.springframework.security:spring-security-oauth2-authorization-server`，版本随 Spring Security：**当前 GA 7.1.1（2026-08-20，FW 7.0.9）**，7.0.x 线最新 7.0.7，下一代 7.2.0-M2（2026-09-24）。包名 `org.springframework.security.oauth2.server.authorization.*` 基本未变，仅少数 config 类移位。
3. **Boot 4 兼容结论**：SAS 1.x 与 Boot 4 / Framework 7 **不兼容**；Boot 4.0+（GA 2025-11-20）配套 `spring-security-oauth2-authorization-server:7.x`，且 Boot 4 **首次自带** `spring-boot-starter-oauth2-authorization-server` 与属性驱动自动配置（`spring.security.oauth2.authorizationserver.*`）。
4. **OAuth 2.1**：token endpoint 支持 5 种 grant：`authorization_code`、`refresh_token`、`client_credentials`、`urn:ietf:params:oauth:grant-type:device_code`、`urn:ietf:params:oauth:grant-type:token-exchange`（RFC 8693）；`implicit`/`password` 从未实现（不在支持列表，OAuth 2.1 草案已废除）；PKCE（RFC 7636）对 public client 强制（`ClientSettings.requireProofKey`）。
5. **Token**：JWT（self-contained）与 opaque（reference）双格式；扩展点 `OAuth2TokenGenerator` / `OAuth2TokenCustomizer`（`JwtEncodingContext` / `OAuth2TokenClaimsContext`）完备，实现类 `JwtGenerator` / `OAuth2AccessTokenGenerator` / `OAuth2RefreshTokenGenerator` / `DelegatingOAuth2TokenGenerator`。
6. **Discovery/JWKS 齐备**（OIDC Provider Configuration + OAuth2 AS Metadata RFC 8414 + JWK Set endpoint）；**密钥轮转无内置调度**，靠自定义 `JWKSource` 提供新旧多把钥匙。
7. **Back-Channel Logout**：OP 侧（AS 向 RP 发 Logout Token）**仍未支持**（issue #18296，2023-05 提出，至今 open）；已支持 OIDC RP-Initiated Logout（`OidcLogoutEndpointFilter`）；客户端侧接收 back-channel logout 已支持（`oidcLogout(backChannel)`）。
8. **DPoP（RFC 9449）**：AS 侧为 Spring Security 7.0 新增（attic 1.5.x 文档/代码中无）；任意 grant 的 token 请求带 `DPoP` header 即绑定，JWT 写入 `cnf.jkt`。
9. **登录页/Consent 页由应用渲染**：登录页 = Spring Security `formLogin`；consent 页 = `authorizationEndpoint.consentPage("/...")` 重定向到自定义页面；device verification 页同理（`verificationUri` + `deviceVerificationEndpoint.consentPage`）。
10. **Starter 二次封装可行**：Boot 4 已提供内存版属性自动配置（覆盖 80% 快速上手场景）；第三方 starter 的真实空位在 JDBC 持久化、密钥管理/轮换、页面定制、多租户、集群 session；社区先例充分（`rwinch/spring-enterprise-authorization-server`、`NotFound403/id-server`）。注意：ticket 中提到的 `spring-authorization-server-experimental` 仓库**不存在**（GitHub 404）。

## 版本与兼容矩阵

| 线 | 最新版本 | 发布日期 | 依赖基线 | 配套 Boot | 状态 |
|---|---|---|---|---|---|
| SAS 1.5.x（独立终代） | 1.5.8 | 2026-06-09 | Sec 6.5.11 / FW 6.2.19 | Boot 3.5.x | 维护中（末代） |
| SAS 1.4.x | 1.4.8 | 2025-12-16 | Sec 6.4.x | Boot 3.4.x | 已终版 |
| SAS 1.3.x | 1.3.7 | 2025-12-16 | Sec 6.3.x | Boot 3.3.x | 已终版 |
| Sec auth-server 7.0.x | 7.0.7 | 2026-08-20 | FW 7.0.x | Boot 4.0.x（GA 2025-11-20，管理 Sec 7.0.0） | 维护中 |
| Sec auth-server 7.1.x（当前） | **7.1.1** | 2026-08-20 | FW 7.0.9 | Boot 4.1.1（2026-08-20） | 当前 GA |
| Sec 7.2.0 | 7.2.0-M2 | 2026-09-24 | — | Boot 4.2（未发布） | 里程碑 |

- Java 基线：**Java 17+**（Spring Security Authorization Server 要求 Java 17 或更高运行时）。
- 关键日期：Spring Security 7.0.0 GA = 2025-11-17（FW 7.0.0）；Spring Boot 4.0.0 GA = 2025-11-20（管理 Sec 7.0.0 / FW 7.0.1）；合并官宣 = 2025-09-11。
- 迁移要点：坐标由 `org.springframework.security:spring-security-oauth2-authorization-server:1.5.x` 换成同坐标 `7.x`；类名与包名保持（官宣原文："类名和包名保持不变，除少量包迁移"）。

## 各扩展点细节

### 1. 授权类型与 OAuth 2.1 强制项

- Token endpoint（`OAuth2TokenEndpointFilter`）支持的 grant 恰为 5 种：`authorization_code`、`refresh_token`、`client_credentials`、`urn:ietf:params:oauth:grant-type:device_code`、`urn:ietf:params:oauth:grant-type:token-exchange`。`implicit` 与 `password` 不在列表中——SAS 从未实现，符合 OAuth 2.1（社区有 password grant 补丁库，如 `Basit-Mahmood/spring-authorization-server-password-grant-type-support`）。
- PKCE：public client（`client_authentication_method=none`）强制；服务端可对任意 client 设 `ClientSettings.requireProofKey(true)`；feature list 将 RFC 7636 列于 Client Authentication 下。
- 客户端认证：`client_secret_basic` / `client_secret_post` / `client_secret_jwt` / `private_key_jwt` / `tls_client_auth` / `self_signed_tls_client_auth` / `none`。
- Device Grant（RFC 8628）：Device Authorization endpoint 与 Device Verification endpoint **默认禁用**（文档 NOTE），需显式启用并自备 verification 页。
- Token Exchange（RFC 8693）与 Device Code 均为一等 grant，feature list 明确标注。
- PAR（RFC 9126）：`pushedAuthorizationRequestEndpoint(...)` configurer（7.0 文档已收录；7.1.1 还在修 `OAuth2PushedAuthorizationRequestUri` 解析 bug）。
- 附带端点：Token Introspection（RFC 7662）、Token Revocation（RFC 7009）、动态客户端注册（RFC 7591 / OIDC Registration，默认禁用）。

### 2. Token 生成与自定义（opaque vs JWT）

- 接口：`OAuth2TokenGenerator<T extends OAuth2Token>`（`@FunctionalInterface`，位于 `...server.authorization.token`）。
- 内置实现：`JwtGenerator`（`OAuth2TokenFormat.SELF_CONTAINED`，JWT）；`OAuth2AccessTokenGenerator`（`OAuth2TokenFormat.REFERENCE`，opaque，`setAccessTokenCustomizer(...)`）；`OAuth2RefreshTokenGenerator`；`DelegatingOAuth2TokenGenerator` 组合前两者+refresh。
- 自定义层：`OAuth2TokenCustomizer<JwtEncodingContext>`（改 JWS header/claims，`JwtGenerator.setJwtCustomizer(...)`，可按 `OAuth2TokenType.ACCESS_TOKEN` vs `id_token` 分支）；`OAuth2TokenCustomizer<OAuth2TokenClaimsContext>`（改 opaque claims）。
- 典型装配（来自 core-model-components 文档）：

```java
@Bean
public OAuth2TokenGenerator<?> tokenGenerator() {
    JwtGenerator jwtGenerator = new JwtGenerator(jwtEncoder);
    OAuth2AccessTokenGenerator accessTokenGenerator = new OAuth2AccessTokenGenerator();
    accessTokenGenerator.setAccessTokenCustomizer(accessTokenCustomizer());
    OAuth2RefreshTokenGenerator refreshTokenGenerator = new OAuth2RefreshTokenGenerator();
    return new DelegatingOAuth2TokenGenerator(
            jwtGenerator, accessTokenGenerator, refreshTokenGenerator);
}
```

- opaque token 需配 introspection endpoint 供资源服务器校验；JWT 走 JWKS。

### 3. Discovery / JWKS / 密钥轮转

- Discovery：`.oidc(Customizer.withDefaults())` 启用 OIDC Provider Configuration（`/.well-known/openid-configuration`）；OAuth2 AS Metadata（RFC 8414）endpoint 支持 `authorizationServerMetadataCustomizer`。
- JWKS：`NimbusJwkSetEndpointFilter`，**仅当注册 `JWKSource<SecurityContext>` bean 时才配置**；getting-started 标准写法为 `RSAKey` + `ImmutableJWKSet`。
- 轮转：框架无内置轮换调度/持久化密钥库；实践是 `JWKSet` 同时含新旧多把钥匙（`JWKSource` 返回按 selector 匹配的钥匙），资源服务器侧"AS 公布新 key 后 Spring Security 自动轮换校验钥匙"（jwt.html 原句）。密钥的生成-存储-定期轮换是标准空位（starter 卖点）。
- `AuthorizationServerSettings` 可定制各 endpoint URI（含 `jwkSetEndpoint(...)`）。

### 4. Logout / Back-Channel Logout

- OP 侧已支持：OIDC RP-Initiated Logout 1.0（`OidcLogoutEndpointFilter` / `...oidc.web` 包下 Logout 相关 7 个类；`postLogoutRedirectUri` 客户端注册项）。
- OP 侧未支持：**Back-Channel Logout（OP 向 RP 的 `backchannel_logout_uri` 推送 Logout Token）**——issue #18296（2023-05-07 提出，12 👍，2026-08 仍活跃无关闭 PR）。`backchannel_logout` 字符串仅出现在 oauth2-client 模块（RP 侧）。
- RP/客户端侧已支持：`oidcLogout(logout -> logout.backChannel(withDefaults()))` + `OidcBackChannelLogoutHandler` + `OidcSessionRegistry`（servlet 与 reactive 均有）。jauth-hub 若同时充当 RP 可直接用。

### 5. DPoP（RFC 9449）

- AS 侧（7.0 新增；attic 1.5.x 文档中无 DPoP）：client 在 token 请求带 `DPoP` header（所有 grant 通用），AS 验证 proof（`DPoPProofVerifier`，被 `OAuth2DeviceCodeAuthenticationProvider`、`OAuth2RefreshTokenAuthenticationProvider` 等引用）并把公钥绑定进 token：JWT 写 `cnf.jkt`，opaque 走 introspection 返回。
- metadata 暴露 `dpop_signing_alg_values_supported`（`OAuth2AuthorizationServerMetadataClaimNames`）。
- 资源服务器侧：`servlet/oauth2/resource-server/dpop-tokens.html`（校验 `ath`、`jkt` 与 DPoP proof 匹配）。
- refresh 时对 public client 校验 DPoP 公钥与原 access token 绑定一致（`OAuth2RefreshTokenAuthenticationProvider` 代码注释明示）。

### 6. 登录页 / Consent 页定制模型

- **页面全部由应用渲染，框架不提供默认 UI**：
  - 登录页：标准 Spring Security `formLogin`；getting-started 示例为两条 `SecurityFilterChain`（AS 链 + 表单链），AS 链用 `LoginUrlAuthenticationEntryPoint("/login")` 对 TEXT_HTML 请求重定向。
  - Consent 页：`authorizationEndpoint(endpoint -> endpoint.consentPage("/custom/consent"))`——仅"重定向到你的页面"模型；开启 consent 靠 `ClientSettings.requireAuthorizationConsent(true)`（Boot 属性 `require-authorization-consent: true`）。官方 sample（attic `samples/default-authorizationserver`）自带 consent 控制器/模板可抄。
  - Device verification 页：`deviceAuthorizationEndpoint(...).verificationUri(...)` + `deviceVerificationEndpoint(...).consentPage(...)`，同样自备页面。
- Boot 4 自动配置也留了 customizer 挂点（其测试展示 `endpoint.consentPage("https://example.com/custom-consent-page")`）。

## Starter 二次封装可行性与限制

**现状（决定了 starter 的定位）**：Boot 4.0 起官方已提供自动配置——`module/spring-boot-security-oauth2-authorization-server`（`OAuth2AuthorizationServerAutoConfiguration`，`@since 4.0.0`；`OAuth2AuthorizationServerProperties` = `spring.security.oauth2.authorizationserver.*`，支持 issuer + 多 client 注册 + `require-authorization-consent`；`OAuth2AuthorizationServerWebSecurityConfiguration` 在存在 `RegisteredClientRepository` / `AuthorizationServerSettings` bean 时自动装配 AS 的 `SecurityFilterChain`；另有 `OAuth2AuthorizationServerJwtAutoConfiguration`）。能力边界：**InMemory** `RegisteredClientRepository`、默认 `HttpSecurity`、无持久化、无密钥管理、无页面。

**可行且有空位的方向**：
1. JDBC/JPA 持久化装配：`JdbcOAuth2AuthorizationService`、`JdbcRegisteredClientRepository`、`JdbcOAuth2AuthorizationConsentService`（框架自带 JDBC 实现，Boot 不自动配）。
2. 密钥管理：生成/持久化（KMS/文件/DB）、定期轮换调度、多钥 `JWKSource`——纯空位。
3. 登录/consent/设备验证页面的默认实现与国际化。
4. 多租户/多 issuer：多条 `SecurityFilterChain` + 各自 `AuthorizationServerSettings`，starter 可模板化。
5. 集群部署：session 管理（Spring Session）、`OidcSessionRegistry` 持久化。
6. DPoP/PAR/introspection 等策略开关与默认值。

**限制与风险**：
- 核心是 `HttpSecurity` DSL，自动配置无法穷举 customizer 挂点；需仿 Boot 4 的 `ObjectPostProcessor` / customizer bean 机制留缝，否则用户会被锁死在你的 DSL 之外。
- `JWKSource` 是单点抽象，无标准"密钥存储"接口，轮换方案属自研（无官方背书）。
- 需跟随 Sec 7.x API 演进（7.2 开发中；7.x 线仍会动 AS API，如 7.1.1 修 PAR URI 解析）。
- GraalVM native/AOT 需自行验证。
- 若只封装 Boot 4 已覆盖的能力（属性→内存 client），价值为零——差异化必须落在持久化/密钥/页面/多租户。

**社区先例**：
- `rwinch/spring-enterprise-authorization-server`（118★，Spring Security 项目负责人 Rob Winch 本人示范的"企业版 SAS 封装"）。
- `NotFound403/id-server`（396★，基于 SAS 的完整 IdP，含大量扩展 grant/页面）。
- `andifalk/custom-spring-authorization-server`（82★）、`ReLive27/spring-security-oauth2-sample`（239★，含 rotating-key、opaque、device-code 示例）。
- 官方 samples 已随仓库归档（attic `samples/`：`default-authorizationserver`、`demo-authorizationserver`、`demo-client`、`messages-resource`、`users-resource`），停留在 1.5.x；Sec 7 时代以 Boot 自动配置 + Spring Security reference 为准。
- **注意**：ticket 里写的 `spring-authorization-server-experimental` 仓库不存在（GitHub 404 已验证）。

## 近期架构级变更（1.x → 当前）

1. **项目合并**：SAS 并入 Spring Security monorepo（`oauth2/oauth2-authorization-server`），独立仓库归档至 spring-attic；1.5.x 为末代（官宣 2025-09-11）。
2. **坐标与版本**：artifact 名不变（`spring-security-oauth2-authorization-server`），groupId 不变（`org.springframework.security`），版本改随 Spring Security（7.0.0 GA 2025-11-17）。业务包 `org.springframework.security.oauth2.server.authorization.*` 保持；少量类移位（如 `OAuth2AuthorizationServerConfiguration` / `OAuth2AuthorizationServerConfigurer` 归入 `org.springframework.security.config.annotation.web.*`）。
3. **文档并入** Spring Security Reference（`servlet/oauth2/authorization-server/`，5 个页面：index / getting-started / configuration-model / core-model-components / protocol-endpoints），旧 SAS how-to 页未迁移；7.0.0 新增 "minimal authorization server configuration"（PR #18153）。
4. **新能力（7.0 相对 1.5）**：AS 侧 DPoP（RFC 9449）、PAR（RFC 9126）进入文档与代码；Boot 4 首次提供官方 starter 与自动配置。
5. **支持线**：1.4.x / 1.3.x 已终版（1.4.8 / 1.3.7，2025-12-16）；1.5.x 继续出补丁（1.5.8，2026-06-09）。

## 来源列表

- 官宣博客（2025-09-11）：https://spring.io/blog/2025/09/11/spring-authorization-server-moving-to-spring-security-7-0
- attic 仓库 README（归档通知 + 1.5.x 末代声明）：https://github.com/spring-attic/spring-authorization-server
- SAS 1.5.8 release（Sec 6.5.11 / FW 6.2.19）：https://github.com/spring-attic/spring-authorization-server/releases/tag/1.5.8
- SAS releases 列表（1.4.8/1.3.7 终版日期）：https://github.com/spring-attic/spring-authorization-server/releases
- Spring Security 7.0.0 GA（2025-11-17，FW 7.0.0，PR #18153）：https://github.com/spring-projects/spring-security/releases/tag/7.0.0
- Spring Security 7.1.1（当前，2026-08-20，FW 7.0.9）：https://github.com/spring-projects/spring-security/releases/tag/7.1.1
- Spring Security releases 列表（7.0.7 / 7.2.0-M2 日期）：https://github.com/spring-projects/spring-security/releases
- Spring Boot 4.0.0 GA（2025-11-20，管理 Sec 7.0.0）：https://github.com/spring-projects/spring-boot/releases/tag/v4.0.0
- Spring Boot 4.1.1（管理 Sec 7.1.1 / FW 7.0.9）：https://github.com/spring-projects/spring-boot/releases/tag/v4.1.1
- AS 特性矩阵（grant/token/端点全表）：https://docs.spring.io/spring-security/reference/7.0/servlet/oauth2/authorization-server/index.html
- AS Getting Started（Java 17、starter、属性自动配置、JWKSource）：https://docs.spring.io/spring-security/reference/7.0/servlet/oauth2/authorization-server/getting-started.html
- AS Protocol Endpoints（grant 列表、consentPage、DPoP、PAR、默认禁用端点）：https://docs.spring.io/spring-security/reference/7.0/servlet/oauth2/authorization-server/protocol-endpoints.html
- AS Core Model Components（OAuth2TokenGenerator/Customizer 示例）：https://docs.spring.io/spring-security/reference/7.0/servlet/oauth2/authorization-server/core-model-components.html
- OAuth2TokenGenerator 接口（spring-security 仓库源码）：https://github.com/spring-projects/spring-security/blob/main/oauth2/oauth2-authorization-server/src/main/java/org/springframework/security/oauth2/server/authorization/token/OAuth2TokenGenerator.java
- DPoPProofVerifier 等 AS 侧 DPoP 代码（GitHub code search）：https://github.com/spring-projects/spring-security/tree/main/oauth2/oauth2-authorization-server/src/main/java/org/springframework/security/oauth2/server/authorization/authentication
- RS 侧 DPoP 文档：https://docs.spring.io/spring-security/reference/7.0/servlet/oauth2/resource-server/dpop-tokens.html
- RP 侧 back-channel logout（client 文档）：https://docs.spring.io/spring-security/reference/7.0/servlet/oauth2/login/logout.html
- OP 侧 back-channel logout 未支持（open issue #18296）：https://github.com/spring-projects/spring-security/issues/18296
- Boot 4 AS 自动配置源码（@since 4.0.0）：https://github.com/spring-projects/spring-boot/tree/main/module/spring-boot-security-oauth2-authorization-server
- Boot 4 AS WebSecurity 自动配置（含 consentPage 测试）：https://github.com/spring-projects/spring-boot/blob/main/module/spring-boot-security-oauth2-authorization-server/src/main/java/org/springframework/boot/security/oauth2/server/authorization/autoconfigure/servlet/OAuth2AuthorizationServerWebSecurityConfiguration.java
- RS JWT 密钥自动轮换陈述：https://docs.spring.io/spring-security/reference/7.0/servlet/oauth2/resource-server/jwt.html
- 社区先例：https://github.com/rwinch/spring-enterprise-authorization-server 、https://github.com/NotFound403/id-server 、https://github.com/ReLive27/spring-security-oauth2-sample 、https://github.com/andifalk/custom-spring-authorization-server
- attic 官方 samples：https://github.com/spring-attic/spring-authorization-server/tree/main/samples
- `spring-authorization-server-experimental` 不存在（404 验证）：https://github.com/spring-projects/spring-authorization-server-experimental
