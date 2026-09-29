# Wayfinder Map: jauth-hub 架构设计

Label: wayfinder:map

> **状态：地图已完成（2026-09-28）**——全部 10 张票关闭、迷雾清空、无范围外遗留。**规格汇总入口：`docs/SPEC.md`（实施宪法）**。等待用户明示"开始实施"（全程不写代码约束至此解除条件：仅用户确认后）。

## Destination

一份锁定的架构规格：GitHub 式 OAuth 2.1/OIDC 认证中心 **jauth-hub**（Java 21 + Spring Boot 4.x + Spring Authorization Server），**双模式**（独立部署 / 内嵌宿主服务），含：v1 功能范围（对比真实 GitHub 功能筛选 34 条清单）、学习型前端页面清单、第三方库与本地 demo 对接的技术细节。终点 = 实施会话可据此直接开工。

## Notes

- 技术栈固定：Java 21 + Spring Boot 4.1.x（`spring-boot-starter-parent` 统一版本管理，`spring-security-oauth2-authorization-server` 等依赖版本随 BOM 走，不单独锁定）；强制 PKCE；无 JWT 过渡期（新项目直接接入）。
- 研究工具：Context7（官方文档）+ GitHub MCP（spring-projects/spring-authorization-server 源码/示例/release）+ WebFetch（GitHub 官方 OAuth 文档）。
- 目标用户（Q3=A+B）：自有新项目生态优先，同时按可开放公共平台演进——风控类功能权重在范围票中再裁。
- 用户体系：认证中心自持用户（用户名/密码）；PostgreSQL（生产）+ H2（开发/测试）。
- 前端定位：简单、教学型——让前端开发者看页面即明白底层认证与交互逻辑。
- 术语表见根目录 CONTEXT.md。
- **用户约束（全程有效）**：任何票都不写实现代码，实施等用户明确确认；06 是用户保留的压轴票，用户后续追加的票同样压在 06 前后最后处理。
- **平台约束（复审裁定）**：v1 仅 JVM 部署，代码守 AOT 友好（少运行时反射、DTO 规整）；GraalVM 原生二进制为 v1.x 实验目标（docs/GRAALVM_NATIVE_IMAGE_SUPPORT.md）。
- 本地 markdown tracker：票在 `issues/`，`Status:`/`Type:`/`Blocked by:` 行表达状态。

## Decisions so far

- [01 探测 Spring Authorization Server 最新 API](issues/01-research-sas-latest.md): SAS 独立项目已终结（2025-09 并入 Spring Security 7，仓库归档）；Boot 4 必须用 `spring-security-oauth2-authorization-server` 7.x（GA 7.1.1 配 Boot 4.1.x / 7.0.7 配 Boot 4.0.x），SAS 1.x 与 Boot 4 不兼容。功能：5 种 grant（含 RFC 8693 token exchange、device）、PKCE 对公开客户端强制、JWT+opaque 双格式、PAR/DPoP 已入 7.0；缺口：OP 侧 Back-Channel Logout、内置密钥轮转调度、页面全靠自渲染。Starter 二次封装可行但价值收窄为 JDBC 持久化/密钥管理/页面/多租户（Boot 4 已自带属性级自动配置）。详见 [research/01-sas-latest.md](research/01-sas-latest.md)。
- [02 GitHub 真实 OAuth 功能清单对比筛选](issues/02-research-github-features.md): 清单实为 26 条：真实核心 11 / 平台级 2 / 部分真实或过时 6 / 虚构 7。最大意外：GitHub 作为用户 IdP **不是** OIDC Provider（无 discovery/JWKS/userinfo/ID token）；PKCE 2025-07 才支持且不强制；限流真实模型是按用户合并桶（5,000/h）而非 client+user 维度；phantom token / RFC 8693 / 降权头 / back-channel logout 均为清单虚构。研究给出的 v1 核心集与缓/弃建议详见报告——注意与研究建议的张力：OIDC 在 Spring 模块里近乎免费，"弃 OIDC"还是"GitHub 对标 + OIDC 兼得"留给票 04 裁决。详见 [research/02-github-features.md](research/02-github-features.md)。
- [04 v1 功能范围裁剪](issues/04-grilling-v1-scope.md): 12 项进 v1（按序：密码→OIDC 全套→scope/consent→客户端/PKCE→opaque+RTR+revoke→**org 安装审批+封顶**→PAT/device//me→Passkey→sudo→限流→看板/审计），含完整三档表与附决（用户不导入、session 可插拔 JDBC/Redis）。v2+：webhook、secret scanning、多 secret、token exchange(配置可开)、back-channel logout 等；出局：phantom token、降权头。数据模型已毕业为票 07。复审补充：账号生命周期极简（管理员建号 + 首启自动建超管 + 自助改密，无邮箱流）；memory 模式禁 PAT/审计降级滚动缓冲/密钥重启即换；v1.0（认证核心）→ v1.1（org 平台层）→ v1.2（Passkey/sudo）三段发布。
- [05 学习型前端页面清单](issues/05-grilling-learning-frontend.md): 10 页按里程碑分档（v1.0 登录/consent/设备/PAT/看板，v1.1 应用管理/安装审批/用户管理，v1.2 Passkey/sudo）+ **`/demo` 真实联调教学区**（对真实端点走完整授权码+PKCE，实时 HTTP 日志）；三层教学呈现（折叠说明块可配置关/HTTP 日志/流程图高亮）；**新增第五模块 `jauth-hub-selfservice`**（域数据自助页，嵌入宿主可选依赖；用户管理页留 app）；UI 中文+i18n 结构、README 中文先行、手写单文件 CSS 无外链。
- [07 数据模型与库表设计](issues/07-grilling-data-model.md): 框架 3 表（registered_client 加 owner 二选一列；authorization 做**令牌 SHA-256 哈希手术**——框架默认明文落库不可接受）+ 自有 9 表（user 含 role/strong_auth_at、org/member、installation 带 ceiling、PAT 存哈希、token_family 熔断、审计只记生命周期不打内省）+ vendored spring_session×2。语义：发行=请求∩consent∩ceiling；多 org 在 consent 页选上下文；scope 目录=代码枚举+i18n 不建表；主键 UUID v7；Flyway 单目录双库 CI 验证。
- [09 统一响应对象抽象](issues/09-grilling-unified-response.md): `ResponseRenderer` SPI（renderSuccess/renderFail 两方法，`@ConditionalOnMissingBean` Bean 条件装配，宿主一个 @Bean 整体换方言）；自有 advice 接异常→委托 SPI，不依赖宿主全局 advice（防误包装协议端点）。端点三分：协议端点 RFC 格式禁包装 / 平台 API（/me）裸 JSON / 管理自助 demo 走 SPI。错误码沿用 spring-plus 分段惯例占 `A05xx/B05xx` 号段（复审修正撞号）；默认实现字段名照抄 SimpleResponse；分页 DTO 字段照抄家族 PageResponse 线上契约（item/total/pageNum/pageSize/totalPage）。
- [08 spring-plus 三件套对接落点](issues/08-grilling-spring-plus-integration.md): 引入矩阵——app 三件套全必选（ENC()/SimpleResponse/@Requires*），starter 唯一可选接入点（检测到 spring-plus-web 自动注册 SimpleResponse 版 ResponseRenderer），core/selfservice/rs-starter 零依赖。已核实：denyAll 红线天然合规、协议错误不流入 SecurityExceptionAdvice、SUPER_ADMIN 映射 = authorities 附加 ROLE_SUPER_ADMIN。demo 区用裸 RestClient；app 敏感配置默认 ENC()；**版本线 1.1.0（Boot 4.1 兼容，用户给定）**。
- [06 压轴技术细节](issues/06-final-tech-details.md): 宿主链共存四规则（只认领 jauth 自有固定端点、@Order 100 可配、嵌入永不兜底、app 模式才给 default 链）；宿主要保护自家接口=自己接 OAuth 协议（rs-starter），仓库增补 **examples/ 内嵌接入示例工程**。demo（Boot 3.5/SAS 1.x）定位纯参考：双链骨架可平移，临时密钥/InMemory/CSRF 关/{noop} 不带走。九项收口全裁：版本=里程碑即版本+semver；CORS 配置默认空；内省缓存 30s；actuator+指标；内省调用方=机密客户端；默认策略表（access 2h/refresh 30d 轮转/PAT 90d/设备码 15min/授权码 5min+PKCE/密钥 90d+14d 重叠）；Passkey 先验原生；制品=4 库上 Central、app 不上、无 BOM。终局：**MIT、Boot 4.1.x BOM + spring-plus 1.1.0、client_credentials/DPoP 默认关**。
- [03 双模式（内嵌/独立）架构与模块划分](issues/03-grilling-dual-mode-architecture.md): 四模块 `core / starter / app / resource-server-starter`（05 修订为五模块：+`selfservice` 自助页可选模块）；starter opt-in 接管式自动配置；存储 memory|jdbc 双实现条件注册（properties 仅播种）；嵌入契约 = 宿主供 `UserDetailsService`（jdbc 再加 `DataSource`），其余一切内置默认可替换；表挂宿主库、SAS 标准表名、Flyway 随 core；**单 issuer + 组织(org)维度**（全局复审改判：GitHub 式平台 = 一个用户池 + org 权限边界；多池需求用多部署顶，不做进程内多租户），仅 `registered_client` 加 org 列；SQL 方言 H2/PostgreSQL 双兼容，不做 MySQL。

## Not yet specified

（无——迷雾已清空，全部并入已关票）

## Out of scope

- 实施与发布到 GitHub（oatelauser/jauth-hub，公开仓库、建议 MIT）：地图终点是规格，发布在后续实施 effort 中完成。本地 git 已 init 以支撑研究协作。
- JWT 旧体系迁移/过渡方案（用户明确：不存在过渡，新项目直接用 OAuth 2.1）。
