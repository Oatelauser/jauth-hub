# 07 数据模型与库表设计

Type: grilling
Status: resolved

## Question

基于票 04 定案的 v1 范围，设计库表全景并出 ER 图：

- 用户 + 凭据表（密码、passkey，为 sudo mode 留近期认证时间位）
- org / 成员 / 角色（管理员审批权的来源）
- 应用注册（SAS `oauth2_registered_client` + org 归属列的接法）
- installation 审批记录与权限封顶（consent 与 org 上限取交集的落点）
- SAS 标准三表（client / authorization / consent）与自有表的关系边界
- PAT、token 族谱（RTR 重放熔断的 series 追踪）、审计事件、限流计数
- 敏感字段哈希策略（密码、client_secret、opaque token 存哈希否）
- 首启超管 seeding 落点（properties 定义 → 启动 seeder，与 Flyway 静态数据的边界）
- 用户表预留 email 列（v1 不启用邮箱流）；PAT 的 jdbc 门控（memory 模式禁用）在仓库层的体现
- Flyway 脚本组织：H2/PostgreSQL 双方言怎么放（同脚本 vs 双目录）

产出：ER 图 + 每表一句话职责 + 双方言脚本组织决议。

## Answer

裁定：Q1=A（框架原生 + 卫星表 + **令牌哈希手术**）、Q2=个人/组织应用并存、P1–P6 全按推荐。

**ER 定稿（框架 3 + 自有 9 + vendored 2）**

框架表（2 张动手术）：
- `oauth2_registered_client`：框架结构 + owner_user_id/owner_org_id **二选一 CHECK 列**（个人应用免安装审批）
- `oauth2_authorization`：框架结构 + **令牌哈希手术**——子类化 `JdbcOAuth2AuthorizationService`，access/refresh 只存 SHA-256、按哈希查询（P1：框架默认明文落库，不可接受）
- `oauth2_consent`：框架原样

自有表：
- `jauth_user`：id/username/password_hash/email(预留)/display_name/**role(SUPERADMIN|USER)**/status/**strong_auth_at**(sudo 位)/created_at（P3）
- `jauth_user_credential`：Passkey 凭据（表随 v1.0 建，v1.2 启用）
- `jauth_org` + `jauth_org_member`（role: OWNER|MEMBER，安装审批权在 OWNER）
- `jauth_installation`：client×org、status、**ceiling_scopes**、approved_by/at
- `jauth_pat`：token_sha256/展示前缀/scopes/expires_at/last_used_at
- `jauth_token_family`：刷新族谱，重放检测 → 整族烧断
- `jauth_audit_event`：追加只写，**只记生命周期事件**（签发/刷新/撤销/consent/登录/审批）；内省**不打审计**（P5），"最近使用"由签发/刷新事件推导

vendored（P2）：`spring_session` + `spring_session_attributes`，框架 DDL 收进 Flyway 目录。

（B1 实施复审补定：原"自有 9 表"实列 8 张，缺的第 9 张为 `jauth_jwk` 签名密钥表——SPEC §6 密钥 90d 轮转 + 14d 重叠 + 2 把共存的落库前提；由 B2 批次以 V4 迁移补齐，总数 14 对齐。）

**语义规则**：
- 发行 scopes = 请求 ∩ consent ∩ installation.ceiling，运行时取交不落表
- 内省富化（终审补）：`/introspect` 响应与 OIDC claims 走**同一个映射扩展点**（04 项 12），默认携带 sub/username/scope/orgs（含角色）——业务资源服务器做细粒度鉴权的统一数据源
- 多 org 歧义：consent 页 org 上下文选择器，ceiling 取所选 org（P4）；个人应用无此步
- scope 目录 = **代码枚举 + i18n 描述，不建表**（P6）
- 超管：properties 定义 → 启动 seeder 写入 role 列

**工程项**：主键 UUID v7；detail JSON 用 TEXT（H2/PG 公共子集）；限流内存计数器无表；密码 bcrypt（Argon2 归 06）；client_secret 框架 PasswordEncoder 编码；Flyway 单脚本目录 + CI 双库验证。
