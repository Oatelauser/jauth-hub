# 06 压轴技术细节：第三方库对接 + 本地 demo 评审

Type: grilling
Status: resolved
Blocked by: 01, 02, 03, 04, 05, 07, 08, 09

## Question

用户指定此票**最后**处理。届时用户提供：

1. 需要对接的第三方库清单（待用户给出）
2. 本地已有的基于 Spring Authorization Server 写的 demo 代码（待用户给出路径）

在此决定：每个第三方库的接入方式与落点（哪个模块、替代还是并存）、demo 代码中可吸收的模式与要规避的问题、依赖版本仲裁、最终技术栈锁定（含 Boot 4 兼容矩阵落地）。

**收口清单（全局复审汇总）**

迷雾遗留 5 项：
1. starter 与宿主既有 SecurityFilterChain/@EnableWebSecurity 的共存与排序规则
2. 库版本策略（semver、API 稳定承诺）
3. token 端点 CORS（公开 SPA 客户端浏览器直连）
4. rs-starter introspection 缓存 TTL 策略（撤销时延 vs 性能）
5. actuator 集成与基础指标（发 token 计数等）

复审新增 4 项：
6. **内省调用方认证**：/introspect 须客户端认证——资源服务器注册为机密客户端的事实写进规格与文档
7. **默认策略表**：access/refresh/PAT/设备码 TTL、密码复杂度与 bcrypt 强度、登录失败锁定、密钥轮转周期与重叠窗口的初值
8. **Passkey 库选择**：先验证 Spring Security 6.4+ 原生 WebAuthn/Passkey 是否够用，够则不引 WebAuthn4J
9. **制品集**：core/starter/selfservice/rs-starter 上 Maven Central（按 docs/MAVEN_CENTRAL_PUBLISHING.md），app 不上 Central（可执行应用），v1 无 BOM，Docker 镜像随发布流程

既定默认值：client_credentials 与 DPoP 框架能力在、v1 默认关（配置可开不宣传）；License 候选 MIT 待用户确认；依赖仲裁 spring-plus=1.1.0、Boot 4.1.x BOM。

## Answer

**Q1 裁定**：四条共存规则通过 + 用户补充两条——①宿主自身接口需要保护时，以**标准 OAuth 协议接入**保护（宿主作为资源服务器/客户端走协议），不搞旁路认证；②仓库增补交付物：**`examples/` 目录创建内嵌接入示例工程**（宿主服务嵌 jauth-hub-starter，演示 UserDetailsService 提供方 + 自家业务接口的 OAuth 保护接入），不发 Central，随仓库走。

**demo 评审结论**（D:\workspace\Java\test-oauth2，前后端均读，定位纯参考）：

后端——双链拆分骨架（authServer 链 HIGHEST_PRECEDENCE + endpointsMatcher / default 链 formLogin）可平移借鉴，注释里的配置实验面正是 properties 播种要暴露的项；不带走：临时 RSA 密钥、InMemory 用户/客户端、CSRF 全关、{noop} 明文、JWT access token。Boot 3.5+SAS 1.x → Security 7 模块包名 API 基本兼容，平移成本低。

前端（front/ 五页 ~1334 行，纯原生 HTML/CSS/JS）——**PKCE 公开客户端完整走法即 /demo 区蓝本**：code_verifier 与 state 存 sessionStorage、callback.html 手工完成 code→token 交换（带 state 校验）、home.html 令牌可视化（原始值 + 解码 payload + 过期/scope 统计）。我们 opaque 令牌下"解码 payload"改为**内省结果展示**，其余平移。中文 UI、暗色渐变 + 白卡片、无框架无外链的设计语言与 05 的样式决议一致，直接沿袭为 jauth-hub 视觉基调。客户端侧 LocalCache AuthorizationRequestRepository 实施期按需再参考。

**九项收口全部裁定**：

1. 宿主链共存：四规则（精确认领/@Order 100 可配/嵌入永不兜底/app 模式才给 default 链）+ examples 示例工程
2. 版本策略：里程碑即版本 v1.0→`1.0.0` / v1.1→`1.1.0` / v1.2→`1.2.0`；semver；1.0 起 API 稳定承诺；废弃提前一个 minor 提示
3. CORS：`jauth-hub.cors.allowed-origins` 默认空（不跨域），仅协议端点生效
4. rs-starter 内省缓存默认 **30s**（撤销时延上限 30s），per-client 可配
5. actuator：app 暴露 health/info/metrics + 发令牌计数等自定义指标；库侧条件注册 micrometer 绑定
6. 内省调用方：资源服务器注册为**机密客户端**（文档化最佳实践模式）
7. 默认策略表（全部 per-client 可覆盖）：access 2h / refresh 30d 用后即轮转 / PAT 默认 90d（可选 30/90/365）/ 设备码 15min / 授权码 5min+强制 PKCE / bcrypt 默认强度 / 密码 ≥8 位无复杂度表演 / 登录连错 5 次锁 15min（走限流器）/ 密钥 90d 轮转 + 旧钥保留 14d + 最多 2 把共存
8. Passkey：v1.2 开工时先验 **Spring Security 7 原生 WebAuthn**，不足再引 WebAuthn4J
9. 制品集：core/starter/selfservice/rs-starter 上 Maven Central（按 docs/MAVEN_CENTRAL_PUBLISHING.md）；app 可执行应用不上 Central；v1 无 BOM；Docker 镜像随发布流程

**终局锁定**：License = **MIT**；依赖仲裁 = Boot 4.1.x BOM 统管 + spring-plus **1.1.0**（app 模块）；client_credentials 与 DPoP 默认关（配置可开不宣传）。

在用户提供材料前不可开票工作。
