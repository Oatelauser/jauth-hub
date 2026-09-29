# 04 v1 功能范围裁剪

Type: grilling
Status: resolved
Blocked by: 01, 02, 03

## Question

基于票 02 的"真实 GitHub 功能对比表"和票 03 的架构决定，把 34 条清单裁成 v1 / v2+ / 出局 三档。已知倾向（Q3=A+B）：

- v1 面向自有新项目生态统一登录，同时不堵死公开平台演进
- OIDC 全套（discovery/JWKS/id_token/userinfo）、强制 PKCE、opaque token + introspection、RTR、/revoke、Consent 自选 scope、授权看板、PAT、基础审计 是 v1 候选核心
- 组织级安装、secret scanning、平台级限流、webhooks 大概率 v2+——待对比表确认
- 双模式架构下，哪些功能只属于独立模式（如授权看板页面）也要标出

产出：v1 功能清单（带优先级顺序）+ 显式推迟/出局列表。

## Answer

三轮裁定汇总：OIDC=A（兼得）、认证=C 带开关按序（密码→Passkey→sudo）、org=**B（v1 全做安装审批+权限封顶）**、增值三件套全进、风控=A（只限流）、用户不导入、session 可插拔（JDBC 默认/Redis 可选）。

**v1（按实施顺序）**

| # | 功能 | 备注 |
|---|---|---|
| 1 | 密码登录 | 默认开 |
| 2 | OIDC 全套：discovery / JWKS+密钥轮转 / id_token / userinfo | 密钥 DB 化（多实例共享），轮转调度自研 |
| 3 | 梯级 scope（read:/write:/admin: 前缀）+ consent 勾选交集 + 增量授权（已授不再问） | |
| 4 | 机密/公开客户端 + redirect 精确白名单 + 强制 PKCE | 比 GitHub 更严 |
| 5 | opaque token + introspection/check 端点 + RTR + 重放熔断 + /revoke | 核心 |
| 6 | org 归属 + **安装审批 + 权限封顶**（org 角色模型一并） | 用户裁定 B，v1 体量最大增量 |
| 7 | PAT + Device Flow + REST /me | 骑 opaque 基建 |
| 8 | Passkey(WebAuthn) | 开关，默认关 |
| 9 | sudo mode（敏感 scope 要求近期认证） | 开关，默认关，依赖 8 |
| 10 | 按用户合并限流 + X-RateLimit-* 头 | |
| 11 | 授权看板 + 一键 Revoke + RP-initiated logout + 基础审计(event+IP+UA) | |
| 12 | UserDetails→OIDC claims 映射扩展点（嵌入契约补条） | |

基建既定：memory|jdbc 双存储、Flyway H2/PG 双方言、Spring Session（JDBC 默认/Redis 可选）、单 issuer + org 维度。

**v2+**：webhook 事件推送、secret scanning、多 Secret 并存轮转、token exchange（模块原生，配置可开不宣传）、redirect 通配 per-URI、back-channel logout（等框架 issue #18296）、fine-grained PAT、组织级高级平台功能。

**出局**：phantom token（rs-starter 内省+缓存已覆盖其需求）、请求头自愿降权（非标虚构）、"严格无 OIDC"模式。

**附决**：存量用户不导入（真要搬是一次性脚本）；实施顺序 1→12 即优先级。

**复审补充（全局二次复审，用户裁定）**

- **账号生命周期极简（Q1=A）**：管理员建号/禁用/重置密码 + 用户自助改密；无自助注册、无邮箱流（不引入 SMTP；表结构预留 email 列，v2 再上）。**首次启动自动创建最高权限用户**（账号由 properties 定义，启动 seeding，非 Flyway 数据）。
- **memory 模式语义（Q2=A）**：PAT 在 memory 模式下禁用（启动告警说明）；审计降级为内存滚动缓冲（明确标注仅调试用途）；签名密钥重启即换新（文档写明面向 demo 场景）。
- **三段发布节奏（Q3 接受）**：范围与顺序不变，分期交付——v1.0 认证核心（表项 1–5、7）→ v1.1 平台层（表项 6：org 安装审批+封顶）→ v1.2 强化层（表项 8–9：Passkey、sudo mode）。每个里程碑可独立发布使用。
