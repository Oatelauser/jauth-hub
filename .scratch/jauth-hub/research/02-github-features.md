# 02 GitHub 真实 OAuth 功能清单对比核实（research findings）

Date: 2026-09-25
Method: 三个并行研究通道，全部以一手来源为准——docs.github.com 在线页面、github/docs 仓库权威数据文件（webhook/audit-log/secret-scanning 的 JSON/YAML 源）、github.blog/changelog、以及对 github.com/.well-known/* 与 token.actions.githubusercontent.com 的直接探测。核实日期 2026-09-25。
Ticket: issues/02-research-github-features.md（清单自称 34 条，实际编号 1–25 + 27 共 26 条，#26 不存在）。

## 一、总览：GitHub 真实模型 vs 清单想象

**GitHub 作为用户 IdP 至今不是 OIDC Provider。** 官方原话："GitHub does not currently implement OpenID Connect in its OAuth flows and does not issue ID tokens for users or apps."（docs.github.com/en/apps/github-authentication-discovery-endpoints）。用户侧令牌全是 opaque 前缀串：`gho_`（OAuth App）、`ghu_`（GitHub App user token）、`ghs_`（installation，1h 过期）、`ghr_`（refresh）、`ghp_`/`github_pat_`（PAT）。清单第一、五、六节大量条目（discovery/JWKS/ID Token/userinfo/phantom token/token exchange/downscoping/back-channel logout）是 Keycloak/OIDC/网关厂商（Kong、Curity）的词汇，GitHub 一律没有。

两个必须知道的 2025–2026 新变化（清单作者不知道、老资料也没有）：
1. **PKCE 已支持**（2025-07-14 changelog）：OAuth Apps 与 GitHub Apps 用户认证均支持，仅 S256，官方"推荐但不强制"，且"GitHub 不区分 public/confidential clients"。实测 RFC 8414 metadata：`code_challenge_methods_supported: ["S256"]`。
2. **OAuth App 也可有过期令牌**（2026-08-14 changelog）：`offline_access` scope 或应用级 "Expire user access tokens" 设置（新应用默认开启），8h access + 6mo refresh，与 GitHub Apps 对齐。回调 URL 也改为最多 10 条 + 每条可切换通配匹配（legacy 单 URL 应用默认开通配）。

还有一个易混淆点：`https://github.com/login/oauth/.well-known/openid-configuration` 与 `/jwks` 实测返回 200——但官方文档明确这是**仅为 MCP 客户端（RFC 9728）发布的 public preview metadata**，不代表用户 OIDC 流。GitHub 真正的 OIDC 只有 Actions 的 workload issuer（token.actions.githubusercontent.com），与用户登录无关。

限流模型澄清：主限制是**按用户合并桶**（该用户所有 PAT + 所有 app 的 user token 共享 5,000/h），不是清单所说的 client_id+user_id 维度；按 app 只有二级限制（token 签发 2,000/h/app）。

## 二、逐条对比表（26 条）

判定图例：✅ 真实核心 / 🟡 真实但平台级（或仅部分主张成立，见说明）/ ❌ 虚构或夸大。v1 建议：必做 / 缓 / 弃。

| 编号 | 清单主张 | 核实结论 | v1 建议 | 理由（一行） | 来源 |
|---|---|---|---|---|---|
| 1 | 密码+Passkey(WebAuthn) 登录；高危操作 MFA 二次验证 | ✅ 真实核心（passkey 一等公民；"sudo mode" 2h 会话、敏感操作重计时；PoP 为企业扩展） | 必做 | 现代账户安全基线，GitHub 已把 passkey 做到可无密码 | [1] [2] |
| 2 | OIDC Discovery `/.well-known/openid-configuration` | ❌ 虚构或夸大——用户 OIDC 不存在；仅 MCP 专用 public preview metadata（`github.com/login/oauth/.well-known/...` 实测 200，文档明示不算 OIDC 实现） | 弃 | v1 按纯 OAuth2 走；若走 OIDC 路线另行立项 | [3] [4] |
| 3 | JWKS 端点 + 密钥轮转 | ❌ 虚构或夸大——用户令牌是 opaque 串，无 JWT 可验；jwks 端点仅 MCP 预览；Actions JWKS 与用户无关 | 弃 | 无 JWT 则无 JWKS 需求 | [3] [5] |
| 4 | 标准 ID Token（iss/sub/aud/exp/auth_time） | ❌ 虚构或夸大——官方原句不发 ID token；Actions JWT 有这些 claim 但属 workload 场景 | 弃 | 同上 | [3] [6] |
| 5 | UserInfo 端点按 scope 裁剪 | ❌ 虚构（OIDC userinfo 实测 404）／🟡 等价物真实：`GET /user` REST 由 `read:user`/`user:email` 门控 | 必做（做 REST `/me`，不做 OIDC userinfo） | GitHub 的真实做法就是 scope 门控的 REST 用户端点 | [7] [8] |
| 6 | scope 梯级与前缀（read:user / write:repo / admin:org） | ✅ 真实核心（部分类目有 read:/write:/admin: 梯级：org/public_key/gpg_key/repo_hook；**无 `write:repo`**，`repo` 整块；GitHub Apps 用 permissions 词汇非 scope） | 必做 | 分类+梯级 scope 是 GitHub 授权词汇核心，注意梯级只覆盖部分类目 | [8] |
| 7 | Consent 页勾选/取消 scope，token 含准许交集 | ✅ 真实核心（文档明确：用户可 uncheck，token 可能少于请求；`Check a token` 返回实际授予集） | 必做 | 防过度授权，GitHub 真实行为 | [8] [9] |
| 8 | 运行时增量授权（只提示新增 scope） | 🟡 部分真实——重新走流程拿**新 token** 真实（每 user/app/scope 组合一个 token，上限 10）；省略 scope 且已授权则静默完成；"只显示新增 scope" 无文档依据 | 缓 | v1 支持重授权换新 token 即可，delta-prompt UI 不做 | [7] |
| 9 | 机密/公开客户端分类（公开强制 PKCE） | 🟡 部分真实——PKCE 2025-07 起支持（仅 S256，推荐不强制）；GitHub **不区分** public/confidential client（device flow 免 secret 是唯一例外） | 必做（支持并强制 PKCE-S256；客户端分类缓） | jauth 比 GitHub 更严是合理加固 | [10] [7] |
| 10 | 严格 redirect_uri 白名单（禁通配符、非 localhost 强制 HTTPS） | 🟡 主张部分过时——白名单真实（最多 10 条）；**通配匹配自 2026-08 起是每 URL 可选项**（legacy 默认开）；loopback 端口可变（RFC 8252）；HTTPS 规则由设置 UI 强制 | 必做（默认精确匹配，通配可选） | 白名单是 OAuth 安全底线 | [7] [11] |
| 11 | 应用"安装"到组织，受组织管理员边界限制 | ✅ 真实核心（installation 模型、`ghs_` 1h、权限不可超安装授予、org 审批权限变更） | 缓 | 安装模型是 GitHub Apps 本质，但 v1 先做用户级授权，组织安装放 v2 | [12] [13] |
| 12 | Opaque token + introspection 端点；网关翻译内部 JWT | 🟡 部分真实——opaque `gho_` 真实；**无 RFC 7662**，等价物是 REST `POST /applications/{client_id}/token`（Check a token）；网关翻译 JWT 是自选架构非 GitHub 功能 | 必做（opaque + check-token 式端点；JWT 翻译层弃） | 内省用 REST 等价物即可 | [9] [14] [3] |
| 13 | Refresh Token Rotation + 重放检测熔断全系列 | ✅ 真实核心（refresh 单次使用，用后旧 access+refresh 同时失效；GitHub Apps 默认启用，OAuth Apps 2026-08 起新 app 默认）／🟡 "重放全家熔断" 未文档化（重放仅 `bad_refresh_token` 报错） | 必做（单次轮转；重放熔断可做但注明超出 GitHub 文档范围） | 轮转真实且已全量默认 | [15] [16] |
| 14 | `/revoke` 撤销端点 | ✅ 真实核心（`DELETE /applications/{client_id}/token` 撤单 token；`DELETE .../grant` 撤整个授权+全部 token） | 必做 | 双端点（token/grant）语义值得照抄 | [9] |
| 15 | 强制 PKCE，废除 implicit/password | ❌ 虚构框架——GitHub **从未支持** implicit/password（metadata 仅 code/refresh/device 三 grant），无"废除"可言；PKCE 支持但不强制 | 必做（只实现 code+device+refresh 并强制 PKCE，天然满足主张） | 从未有过就不存在废除 | [7] [4] |
| 16 | Phantom Token 模式 | ❌ 虚构或夸大——github/docs 全文 0 命中；Kong/Curity 网关词汇 | 弃 | 网关架构选型，不是 IdP 功能 | [17] |
| 17 | Token Exchange（RFC 8693） | ❌ 虚构或夸大——实测 `grant_types_supported` 无 token-exchange；Actions 只有 subject 定制无交换 grant | 弃 | 微服务降权属内部架构 | [4] |
| 18 | 请求头自愿降权（downscoping） | ❌ 虚构或夸大——权限在签发/安装时固定，无任何请求级收窄机制 | 弃 | 无此机制 | [14] [12] |
| 19 | 授权变更 Webhooks | ✅ 真实核心（GitHub App 有 `github_app_authorization`/`revoked` webhook，默认送达不可退订）／🟡 仅此而已：改密推送不存在，OAuth App 完全无 webhook | 缓 | v2 再做撤销事件推送 | [18] [19] |
| 20 | OIDC Back-Channel Logout | ❌ 虚构或夸大——github/docs 全文 0 命中 | 弃 | GitHub 无任何 logout 通知机制 | [18] |
| 21 | client_id+user_id 维度限流 + X-RateLimit-* | 🟡 头真实、模型主张错——`x-ratelimit-limit/remaining/used/reset/resource` 真实（5,000/h 用户令牌、60/h 匿名、15,000/h GHEC 应用）；但主桶是**按用户合并**（跨所有 app+PAT），按 app 仅有二级限制（2,000 token 签发/h/app） | 必做（限流头 + 按用户桶） | 限流头便宜且是 API 卫生 | [20] |
| 22 | 凭证泄漏扫描自动熔断（secret scanning 联动） | 🟡 真实但平台级——`ghp_/github_pat_/gho_/ghs_/ghr_`/SSH key 均为一等扫描模式（validity check + push protection）；公开仓库泄漏 PAT **自动撤销**；私有仓库可一键 Report leak | 缓 | 平台级能力，jauth 规模到一定程度再做 | [21] [22] |
| 23 | 用户"已授权应用"看板 + 一键 Revoke | ✅ 真实核心（Settings > Applications 两个 tab + Revoke/Revoke all；对应 `DELETE .../grant`）；注意 `GET /applications/grants` 在 github.com 已随 OAuth Authorizations API 移除，仅 GHES 存在 | 必做 | 用户侧授权可见性是信任基础 | [23] [24] |
| 24 | 开发者多 Secret 并存轮转（零停机） | 🟡 真实但平台级——GitHub App 多 client secret（可各自删除，audit event 佐证）+ 私钥最多 25 把官方明说用于零停机轮转；OAuth App 文档仍写单一 secret（audit taxonomy 暗示已变化）；client secret 数量无官方上限文档 | 缓 | v1 单 secret + 重置即可，多 secret 放 v2 | [25] [26] |
| 25 | 审计日志（事件+IP+UA） | ✅ 真实核心（org/enterprise audit log REST + 用户 security log 90 天；每条事件含 `user_agent`/`token_scopes`；oauth_authorization.* / integration.revoke_* 事件齐全）；注意 actor IP **默认不显示**，需 org/enterprise 显式开启 | 必做（基础版：event+UA+可选 IP） | 审计是底线，IP 可做成开关 | [27] [28] [29] |
| 27 | PAT（细粒度/经典） | ✅ 真实核心（fine-grained：按仓库+细权限+过期+org 审批策略；classic：scope 制） | 缓 | 真实但 v1 先 OAuth 流，脚本/CI 场景明确后再加 | [30] [5] |

统计（26 条）：真实核心 11（1,6,7,9,11,13,14,19,23,25,27）· 真实但平台级 2（22,24）· 部分真实/主张需修正 6（5,8,10,12,15,21）· 虚构或夸大 7（2,3,4,16,17,18,20）。

## 三、给范围票（04）的裁剪建议汇总

**v1 必做（GitHub 验证过的最小核心）：**
- 账户：Passkey 登录 + sudo-mode 式二次验证（#1）
- 授权：scope 分类+梯级设计（#6）、consent 页可勾选/取消（#7）、redirect 白名单默认精确匹配（#10）、PKCE S256 强制（#9/#15 合并处理——只实现 authorization_code + device + refresh 三 grant）
- 令牌：opaque 前缀 token + REST 式 check/reset/delete 端点（#12/#14，含 token 与 grant 双语义）、refresh 单次轮转 + 过期令牌默认开启（#13）
- 用户侧：已授权应用看板 + 一键撤销（#23）、REST `/me` + scope 门控（#5 的 GitHub 式实现）
- 运维：X-RateLimit-* 响应头 + 按用户合并桶（#21）、基础审计日志 event+UA（#25）

**v1 缓做：** 组织级 app 安装模型（#11）、增量授权 delta-prompt（#8）、撤销事件 webhook（#19）、secret scanning 联动（#22）、多 secret 零停机轮转（#24）、PAT 体系（#27）。

**v1 弃做（清单虚构，属 Keycloak/OIDC/网关想象）：** OIDC discovery/JWKS/ID Token/userinfo（#2/#3/#4/#5 的 OIDC 形态）、Phantom Token（#16）、RFC 8693 Token Exchange（#17）、请求头降权（#18）、Back-Channel Logout（#20）。若产品日后要打"OIDC 兼容"牌，应作为独立路线立项，而不是塞进"GitHub 式"范围。

**修正清单的两处事实错误：** "禁通配符" 已过时（2026-08 起通配是每 URL 选项）；"client_id+user_id 维度限流" 模型不对（按用户合并桶）。

## 来源

- [1] https://docs.github.com/en/authentication/authenticating-with-a-passkey/about-passkeys
- [2] https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/sudo-mode
- [3] https://docs.github.com/en/apps/github-authentication-discovery-endpoints
- [4] https://github.com/.well-known/oauth-authorization-server/login/oauth （实测 RFC 8414 metadata）
- [5] https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/about-authentication-to-github （token 前缀表）
- [6] https://docs.github.com/en/actions/reference/openid-connect-reference （Actions OIDC，非用户）
- [7] https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps
- [8] https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/scopes-for-oauth-apps
- [9] https://docs.github.com/en/rest/apps/oauth-applications （check/reset/delete token、delete grant）
- [10] https://github.blog/changelog/2025-07-14-pkce-support-for-oauth-and-github-app-authentication/
- [11] https://github.blog/changelog/2026-08-14-multiple-redirect-uris-and-token-refresh-for-oauth-apps/
- [12] https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/authenticating-as-a-github-app-installation
- [13] https://docs.github.com/en/apps/using-github-apps/approving-updated-permissions-for-a-github-app
- [14] https://docs.github.com/en/rest/apps/oauth-applications#check-a-token
- [15] https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/refreshing-user-access-tokens
- [16] https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps#expiring-access-tokens
- [17] https://github.com/search?q=repo%3Agithub%2Fdocs+%22phantom+token%22&type=code （0 命中）；Kong/Curity 为该词来源
- [18] https://docs.github.com/en/webhooks/webhook-events-and-payloads （含 github_app_authorization；全文无 back-channel/password 事件）
- [19] https://github.com/github/docs/blob/main/src/webhooks/data/fpt/github_app_authorization.json
- [20] https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api
- [21] https://docs.github.com/en/code-security/reference/secret-security/supported-secret-scanning-patterns
- [22] https://docs.github.com/en/code-security/tutorials/remediate-leaked-secrets/remediating-a-leaked-secret ；https://github.blog/changelog/2024-10-02-secret-scanning-on-demand-revocation-for-github-pats-public-beta/
- [23] https://docs.github.com/en/apps/oauth-apps/using-oauth-apps/reviewing-your-authorized-oauth-apps
- [24] https://docs.github.com/en/apps/using-github-apps/reviewing-and-revoking-authorization-of-github-apps
- [25] https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/managing-private-keys-for-github-apps （最多 25 把私钥零停机轮转）
- [26] https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authenticating-to-the-rest-api-with-an-oauth-app
- [27] https://docs.github.com/en/rest/enterprise-admin/audit-log
- [28] https://docs.github.com/en/organizations/keeping-your-organization-secure/managing-security-settings-for-your-organization/audit-log-events-for-your-organization
- [29] https://docs.github.com/en/organizations/keeping-your-organization-secure/managing-security-settings-for-your-organization/displaying-ip-addresses-in-the-audit-log-for-your-organization
- [30] https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/creating-a-personal-access-token
