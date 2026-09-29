# 01 探测 Spring Authorization Server 最新 API 与功能

Type: research
Status: resolved

## Question

Spring Authorization Server（SAS）当前最新版本是什么？与 Spring Boot 4.x / Spring Framework 7 的兼容矩阵如何？最新 API 与功能面盘点：

- 支持的授权类型与 OAuth 2.1 强制项（PKCE、implicit/password 是否已移除、client_credentials、refresh、device_code、token exchange RFC 8693）
- Opaque access token vs JWT 的生成与自定义（`OAuth2TokenGenerator` / `OAuth2TokenCustomizer` 扩展点现状）
- Discovery / JWKS / 密钥轮转、Back-Channel Logout、DPoP、Device Grant 等支持现状
- 登录页 / Consent 页的定制模型（页面由谁渲染、如何替换）
- 在 SAS 之上做二次封装 starter（自动配置库）的可行性与已知限制（社区先例：spring-authorization-server-experimental 等）
- 近期 release notes 中影响架构的大变更（1.x → 当前）

工具约束：Context7（SAS / Spring Security 文档）+ GitHub MCP（spring-projects/spring-authorization-server 的 releases、samples、issues）+ WebFetch 官方文档。产出结论需带来源 URL。

## Answer

1. SAS 独立项目已终结：2025-09-11 官宣并入 Spring Security 7.0，仓库归档至 spring-attic，1.5.x 为末代（最新 1.5.8，2026-06-09，配 Boot 3.5 / Sec 6.5）。
2. 当前版本即 Spring Security 的 `spring-security-oauth2-authorization-server` 模块：GA 7.1.1（2026-08-20，FW 7.0.9，配 Boot 4.1.x）；7.0.x 线 7.0.7 配 Boot 4.0.x；Java 17+。
3. Boot 4/FW 7 兼容结论：SAS 1.x 不兼容 Boot 4；须换 7.x 模块，且 Boot 4 自带 `spring-boot-starter-oauth2-authorization-server` + `spring.security.oauth2.authorizationserver.*` 属性自动配置（InMemory）。
4. Grant：authorization_code / refresh_token / client_credentials / device_code / token-exchange（RFC 8693）共 5 种；implicit、password 从未支持；PKCE 对 public client 强制；新增 PAR（RFC 9126）与 AS 侧 DPoP（RFC 9449，`cnf.jkt`）。
5. Token：JWT（`JwtGenerator`）与 opaque（`OAuth2AccessTokenGenerator`）双格式，`OAuth2TokenGenerator` / `OAuth2TokenCustomizer`（`JwtEncodingContext` / `OAuth2TokenClaimsContext`）扩展点完备。
6. Discovery/JWKS 齐备（OIDC discovery + RFC 8414 metadata + JWK Set endpoint）；密钥轮转无内置调度，靠自定义 `JWKSource` 多钥并存。
7. Logout：OP 侧支持 RP-Initiated Logout；OP 侧 Back-Channel Logout 仍未支持（issue #18296 open）；RP 侧接收 back-channel 已支持。
8. 登录/consent/设备验证页均由应用渲染（`formLogin` + `consentPage(...)` / `verificationUri(...)` 重定向模型），框架无默认 UI。
9. Starter 可行：Boot 4 已覆盖属性→内存 client 的基础场景；第三方空位 = JDBC 持久化（`Jdbc*Service`）、密钥生成/轮换、页面、多租户、集群 session；先例 rwinch/spring-enterprise-authorization-server、NotFound403/id-server；注意 `spring-authorization-server-experimental` 不存在（404）。

详见调研报告：`D:\workspace\CC\jauth-hub\.scratch\jauth-hub\research\01-sas-latest.md`（含完整来源 URL）。
