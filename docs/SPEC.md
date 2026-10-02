# jauth-hub 架构规格（SPEC）

> **实施宪法**：本文是全部架构决议的唯一汇总入口，实施会话从本文开工。细节深读入口在每节链接的原票（`.scratch/jauth-hub/`）。本文与原票冲突时，以本文为准并同步修票。
>
> 状态：2026-09-28 wayfinder 地图走完（10/10 票关闭），规格锁定，尚未编写任何实现代码。

## 0. 一句话定位

对标 GitHub 的 OAuth 2.1 + OIDC 认证中心：**双模式**（独立部署 / 内嵌宿主服务）、开源公共库 + 参考应用，为自有新项目生态提供统一登录、认证、鉴权，并为开放平台形态预留演进。

## 1. 技术栈（锁死）

| 项 | 决议 | 原票 |
|---|---|---|
| 语言/框架 | Java 21 + Spring Boot 4.1.x，`spring-boot-starter-parent` BOM 统管版本，**依赖不单独锁版** | [01](../.scratch/jauth-hub/issues/01-research-sas-latest.md) |
| 授权服务器 | `spring-security-oauth2-authorization-server`（Spring Security 7 模块，原 SAS 延续；SAS 1.x 与 Boot 4 不兼容） | [01](../.scratch/jauth-hub/issues/01-research-sas-latest.md) |
| 家族件 | spring-plus **1.1.0**（web/security/boot 三件套），仅 app 模块必选 | [08](../.scratch/jauth-hub/issues/08-grilling-spring-plus-integration.md) |
| 数据库 | H2（PostgreSQL 兼容模式）+ PostgreSQL **双兼容 SQL**，不承诺 MySQL；memory\|jdbc 双实现条件注册（`jauth-hub.storage`） | [03](../.scratch/jauth-hub/issues/03-grilling-dual-mode-architecture.md) |
| 会话 | Spring Session：JDBC 默认（零新增基建）、Redis 可选（引依赖即切） | [04](../.scratch/jauth-hub/issues/04-grilling-v1-scope.md) |
| 前端 | **双皮**（[10](../.scratch/jauth-hub/issues/10-frontend-form.md)）：默认 = Thymeleaf SSR + 手写单文件 CSS（无框架）；v1.4 增 headless 皮 = 信任面四页 JSON API + 根目录 `jauth-hub-front`（Vue 3 + Vite，产物随制品、认证中心同域名）。信任面页面永远是 jauth-hub 自家的；自助面可换可缺；接入方前端形态无关。教学三层保留但不绑架架构；**运行时零外链永守**（框架须打包进制品）。UI 中文 + i18n 资源结构（messages_en 骨架） | [05](../.scratch/jauth-hub/issues/05-grilling-learning-frontend.md) · [10](../.scratch/jauth-hub/issues/10-frontend-form.md) |
| 运行时 | v1 仅 JVM；代码守 AOT 友好（少运行时反射）；GraalVM 原生 = v1.x 实验目标 | 地图 Notes |
| 协议 | 强制 PKCE；implicit/password 不存在；client_credentials 与 DPoP 框架能力在、**默认关**（配置可开不宣传） | [06](../.scratch/jauth-hub/issues/06-final-tech-details.md) |
| License | **MIT** | [06](../.scratch/jauth-hub/issues/06-final-tech-details.md) |
| 错误码 | spring-plus 家族分段惯例，jauth-hub 占 **`A05xx`（客户端/授权请求错）+ `B05xx`（内部错）**；默认实现字段名照抄 SimpleResponse（code/message/data） | [09](../.scratch/jauth-hub/issues/09-grilling-unified-response.md) |

## 2. 模块结构（五模块 + examples）

```
jauth-hub-core                        协议+领域实现，无自动配置、零 spring-plus 依赖
├── client/authorization/consent      RegisteredClientRepository 等的内存+JDBC 双实现
├── token/                            opaque/JWT 定制、刷新轮转、JWK 生成与轮转调度（DB 化）
├── web/                              默认 Thymeleaf 登录/consent/设备页 + 控制器
├── org/                              组织实体与上下文（单 issuer，无租户路由）
├── flyway/                           迁移脚本（H2/PG 双兼容单目录）
jauth-hub-starter                     opt-in 接管式自动配置；唯一可选接入点：classpath 有
                                      spring-plus-web 时自动注册 SimpleResponse 版 ResponseRenderer
jauth-hub-selfservice                 域数据自助页（看板/PAT/应用管理），app 依赖、宿主可选依赖
jauth-hub-app                         独立部署壳：自持用户库、用户管理页、/demo 教学区、
                                      spring-plus 三件套全必选
jauth-hub-resource-server-starter     资源服务器薄封装：预接线 introspection（缓存 30s）+ scope→权限映射
examples/                             内嵌接入示例工程（宿主嵌 starter + OAuth 保护自家接口演示）
```

关键机制（[03](../.scratch/jauth-hub/issues/03-grilling-dual-mode-architecture.md)）：
- **接管式自动配置**：引 jauth-hub-starter 即 jauth-hub 装配链生效，Boot 4 自带内存配置让位
- **嵌入契约**：宿主必须提供 `UserDetailsService`（jdbc 模式再加 `DataSource`）；其余一切内置默认、`@ConditionalOnMissingBean`/模板覆盖逐个可替换
- **宿主链共存四规则**：jauth 链只认领自有固定端点（协议端点+/login/consent/设备页）；`@Order` 默认 100 可配；嵌入模式**永不创建 catch-all 链**；default 链只由 app 提供（管理链 denyAll，合 spring-plus 红线）。宿主自家接口要保护 = 自己接 OAuth 协议（rs-starter），examples/ 给示范
- **properties 播种**：`jauth-hub.clients[n].*` 启动 upsert 进活动仓库，不是第二真源
- v1.4 增根目录 `jauth-hub-front/`：Vue 3 + Vite 分离前端工程（信任面四页的自家备选皮 + 生态示范），不在 Maven reactor，部署于认证中心同域名（[10](../.scratch/jauth-hub/issues/10-frontend-form.md)）

## 3. 域模型与数据（14 表，[07](../.scratch/jauth-hub/issues/07-grilling-data-model.md)）

模型：**单 issuer + 组织(org)维度**——全网一个用户池，org 作权限边界，一个用户可属多 org；多用户池需求由多部署满足（嵌入模式天然每宿主一池）。个人应用与组织应用并存（`owner_user_id`/`owner_org_id` 二选一 CHECK）。

| 表 | 职责 |
|---|---|
| `oauth2_registered_client` | 框架 client 注册 + owner 二选一列（个人应用免安装审批） |
| `oauth2_authorization` | 框架令牌元数据 + **令牌哈希手术**（子类化 Jdbc*Service，access/refresh 只存 SHA-256） |
| `oauth2_consent` | 框架原样：用户↔client 已授 scope |
| `jauth_user` | 用户：含 role(SUPERADMIN\|USER)、strong_auth_at(sudo 位)、email(预留) |
| `jauth_user_credential` | Passkey 凭据（v1.0 建表，v1.2 启用） |
| `jauth_org` / `jauth_org_member` | 组织与成员（OWNER\|MEMBER，审批权在 OWNER） |
| `jauth_installation` | 安装：client×org、ceiling_scopes 封顶、审批人/时间；两步制（流向 A，2026-09-30 拍板，B8 落地）——任何登录用户 request（requested_by/requested_scopes，V6 列）→ OWNER approve/reject，APPROVED 可 revoke，REJECTED/REVOKED 可重发 |
| `jauth_pat` | PAT：token_sha256/展示前缀/scopes/过期/last_used（名称列 B5 实施发现缺失，B10 以 V7 加列迁移补——当前创建面仅 scope+有效期） |
| `jauth_token_family` | 刷新族谱：重放检测→整族烧断 |
| `jauth_audit_event` | 追加只写，**只记生命周期事件**（签发/刷新/撤销/consent/登录/审批）；内省不打审计 |
| `jauth_jwk` | 签名密钥（B1 复审补定，原"14 表"缺的第 14 张）：kid/算法/密钥材料/状态(ACTIVE\|RETIRING\|RETIRED)/created_at——支撑 §6 的 90d 轮转 + 14d 重叠 + 2 把共存；B2 批次以 V4 迁移落表 |
| `spring_session` ×2 | vendored DDL 进 Flyway |

语义规则：
- 发行 scopes = **请求 ∩ consent ∩ installation.ceiling**（运行时取交）；多 org 歧义在 consent 页选上下文；个人应用无此步
- **内省富化**：`/introspect` 响应与 OIDC claims 走同一映射扩展点，默认带 sub/username/scope/orgs（含角色）——业务侧细粒度鉴权的数据源
- scope 目录 = 代码枚举 + i18n 描述，不建表
- 超管：properties 定义 → 启动 seeder；主键 UUID v7；审计 detail 用 TEXT
- memory 模式语义：PAT 禁用（启动告警）、审计降级内存滚动缓冲（标注仅调试）、密钥重启即换（面向 demo）

## 4. 端点与响应（[09](../.scratch/jauth-hub/issues/09-grilling-unified-response.md)）

**端点三分**：

| 端点类 | 响应形态 |
|---|---|
| OAuth2/OIDC 协议端点（/authorize /token /introspect /revoke /userinfo /device…） | RFC 标准 `error`/`error_description`，**禁止包装** |
| 平台 API（/me） | 裸用户 JSON（对齐 userinfo 与 GitHub /user） |
| 管理/自助/demo 接口 | ResponseRenderer SPI 统一响应 |

**SPI**：

```java
public interface ResponseRenderer {
    Object renderSuccess(Object data);
    Object renderFail(String code, String message);
}
```

`@ConditionalOnMissingBean` 条件装配；jauth 自有 advice 接 `JauthException` 委托 SPI（不依赖宿主全局 advice，防误包装协议端点）；分页走 data 携带 `item/total/pageNum/pageSize/totalPage`（照抄家族 PageResponse 线上契约）。

协议细节：内省调用方须为**机密客户端**（文档化模式）；CORS `jauth-hub.cors.allowed-origins` 默认空、仅协议端点；rs-starter 内省缓存默认 **30s**。

## 5. 功能范围与里程碑（[04](../.scratch/jauth-hub/issues/04-grilling-v1-scope.md)）

| 里程碑 | 内容 |
|---|---|
| **v1.0 认证核心** | 密码登录、OIDC 全套（discovery/JWKS+轮转/id_token/userinfo）、梯级 scope + consent 勾选交集 + 增量授权、机密/公开客户端 + 精确 redirect 白名单 + 强制 PKCE、opaque + 内省 + RTR 熔断 + /revoke、PAT + Device Flow + /me、claims 映射扩展点 |
| **v1.1 平台层** | org 归属 + 安装审批 + 权限封顶（org 角色模型） |
| **v1.2 强化层** | Passkey（开关，默认关）、sudo mode（开关，依赖 Passkey） |
| **v1.3 滑账清剿**（进行中） | 老账 ①–⑧ + v1.2 新账 2/3/6 收口；批次 T0+D0–D6 见 [v1.3-kickoff](../.scratch/jauth-hub/v1.3-kickoff.md) |
| **v1.4 headless 皮** | 信任面四页（登录/consent/设备/sudo）JSON API 化 + 根目录 `jauth-hub-front`（Vue 3 + Vite 分离前端，产物随制品、同域名）——[10](../.scratch/jauth-hub/issues/10-frontend-form.md) |
| 横切 | 按用户合并限流 + X-RateLimit-*、授权看板 + 一键 Revoke + RP-initiated logout + 基础审计、UserDetails→claims 映射 |

v2+：webhook、secret scanning、多 Secret 轮转、token exchange（配置可开）、redirect 通配 per-URI、back-channel logout（等框架 issue #18296）、fine-grained PAT、邮箱流（注册验证/找回密码）。出局：phantom token、请求头降权、"严格无 OIDC"模式。附决：存量用户不导入。

研究底稿：[GitHub 真实功能对比表](../.scratch/jauth-hub/research/02-github-features.md)（26 条：核心 11/平台 2/过时 6/虚构 7）。

## 6. 安全与默认策略（[06](../.scratch/jauth-hub/issues/06-final-tech-details.md)）

全部 per-client 可覆盖：

| 参数 | 默认 |
|---|---|
| access token | 2h |
| refresh token | 30d，用后即轮转，重放→整族熔断（**公开客户端不发 refresh token**——框架防线，B7 实测确认） |
| PAT | 90d（可选 30/90/365） |
| 设备码 / 授权码 | 15min / 5min + 强制 PKCE |
| 密码 | ≥8 位（无复杂度表演）；bcrypt 默认强度（Argon2 缓） |
| 登录防爆破 | 连错 5 次锁 15min（限流器实现） |
| 签名密钥 | 90d 轮转 + 旧钥保留 14d + 最多 2 把共存（DB 化 JWKSource，多实例共享） |

保持：登录/consent 表单 CSRF、精确 redirect 匹配、client_secret 框架编码 + app 配置 ENC() 静态加密。

## 7. 页面与教学（[05](../.scratch/jauth-hub/issues/05-grilling-learning-frontend.md)）

10 页按里程碑：v1.0 登录/consent/设备验证/PAT/看板 → v1.1 应用管理/安装审批/用户管理（app 专属）→ v1.2 Passkey/sudo。

**`/demo` 真实联调教学区**（app 内置）：对真实端点完整走授权码 + PKCE（code_verifier/state 存 sessionStorage、callback 手工 code→token 交换），实时展示 HTTP 请求/响应日志，最后持 token 调受保护接口——蓝本即参考 demo 的 `front/callback.html`，"解码 payload"环节替换为**内省结果展示**。

三层教学：折叠"发生了什么"说明块（默认开、`jauth-hub.educational=false` 关）/ demo HTTP 日志 / 流程图高亮当前步骤。视觉基调沿袭参考 demo：中文、暗色渐变 + 白卡片、零依赖。

## 8. 工程约定

- 质量门禁按 [AGENTS.md](../AGENTS.md)：p3c（黄山版）/Spotless/SpotBugs+FindSecBugs/OWASP dep-check，编辑期 hook + 交付前 AI 全量评审
- 版本策略：里程碑即版本（v1.0→`1.0.0`、v1.1→`1.1.0`、v1.2→`1.2.0`），semver，1.0 起 API 稳定承诺，废弃提前一个 minor
- 制品：core/starter/selfservice/rs-starter 上 Maven Central（按 [MAVEN_CENTRAL_PUBLISHING.md](MAVEN_CENTRAL_PUBLISHING.md)）；app 不上 Central；v1 无 BOM；Docker 镜像随 [RELEASE_PROCESS.md](RELEASE_PROCESS.md)
- Passkey 选型：已验证采纳 **Spring Security 7 原生 `spring-security-webauthn`**（SS BOM 统管 7.1.1，webauthn4j-core 0.31.9 为其传递依赖；2026-10-01，[research/03](../.scratch/jauth-hub/research/03-passkey-selection.md)）
- actuator：app 暴露 health/info/metrics + 发令牌计数等自定义指标；库侧条件注册 micrometer 绑定
- 参考 demo（纯参考）：`D:\workspace\Java\test-oauth2`（前后端）。吸收：双链骨架、PKCE 客户端走法、设计语言；不带走：临时密钥/InMemory/CSRF 关/{noop}/JWT access token

## 附录：决议索引

研究：[01 SAS/Security7 探测](../.scratch/jauth-hub/research/01-sas-latest.md) · [02 GitHub 真实功能对比](../.scratch/jauth-hub/research/02-github-features.md)
决议：[03 架构](../.scratch/jauth-hub/issues/03-grilling-dual-mode-architecture.md) · [04 范围](../.scratch/jauth-hub/issues/04-grilling-v1-scope.md) · [05 页面](../.scratch/jauth-hub/issues/05-grilling-learning-frontend.md) · [06 技术细节](../.scratch/jauth-hub/issues/06-final-tech-details.md) · [07 数据模型](../.scratch/jauth-hub/issues/07-grilling-data-model.md) · [08 spring-plus 对接](../.scratch/jauth-hub/issues/08-grilling-spring-plus-integration.md) · [09 统一响应](../.scratch/jauth-hub/issues/09-grilling-unified-response.md) · [10 前端形态](../.scratch/jauth-hub/issues/10-frontend-form.md)
地图：[map.md](../.scratch/jauth-hub/map.md)
