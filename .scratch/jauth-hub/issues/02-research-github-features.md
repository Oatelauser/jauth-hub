# 02 GitHub 真实 OAuth 功能清单对比筛选

Type: research
Status: resolved

## Question

以 GitHub 官方文档为准（docs.github.com 的 OAuth apps / GitHub Apps / scopes / PAT / token 过期与撤销等，可用 WebFetch/WebSearch + GitHub REST API 文档），核实下方"34 条清单"中每一条：是 GitHub 真实核心功能、真实但平台级（可缓）、还是清单虚构/夸大。产出对比表 + 每条给出 jauth-hub v1 建议（必做/缓/弃），供范围票（04）裁决。

对比基准清单（另一智能体产出，未经核实）：

一、账户与身份认证
1. 密码登录 + Passkey(WebAuthn)；高危 scope 触发 MFA 二次验证
2. OIDC Discovery 端点 /.well-known/openid-configuration
3. JWKS 端点 + 密钥轮转
4. 标准 ID Token 发放（iss/sub/aud/exp/auth_time）
5. UserInfo 端点按 scope 裁剪返回

二、权限与细粒度授权
6. scope 梯级与前缀（read:user / write:repo / admin:org）
7. Consent 页用户勾选/取消 scope，token 只含准许交集
8. 运行时增量授权（只提示新增 scope）

三、客户端与开发者生态
9. 机密客户端（client_secret）/ 公开客户端（强制 PKCE）分类
10. 严格 redirect_uri 白名单（禁通配符、非 localhost 强制 HTTPS）
11. 应用"安装"到组织，权限受组织管理员边界限制（GitHub Apps installations）

四、令牌管理与安全
12. Opaque access token + introspection 端点；网关翻译为内部 JWT
13. Refresh Token Rotation + 重放检测熔断全系列
14. /revoke 撤销端点
15. 强制 PKCE，废除 implicit/password

五、令牌高级形态
16. Phantom Token 模式（网关翻译不透明令牌）
17. Token Exchange（RFC 8693）微服务横向降权
18. 请求头自愿降权（上下文感知 downscoping）

六、生态联动
19. 授权变更 Webhooks（撤销/改密时推送开发者）
20. OIDC Back-Channel Logout 全网登出

七、风控防刷
21. client_id+user_id 维度限流 + X-RateLimit-* 响应头
22. 凭证泄漏扫描自动熔断（secret scanning 联动）

八、审计与企业控制
23. 用户"已授权应用"看板 + 一键 Revoke
24. 开发者多 Secret 并存轮转（Current/Pending 零停机）
25. 审计日志（事件+IP+UA）

九、渐进迁移
27. Personal Access Tokens（细粒度/经典，脚本与 CI 用）

注意：GitHub 的 OAuth 实现并不完全遵循 OIDC（无 discovery/userinfo——需核实），清单里部分条目可能是作者套用 Keycloak/OIDC 想象的"GitHub 式"，这正是要甄别的。产出须带来源 URL。

## Answer

清单实为 26 条（编号 1–25+27，#26 不存在，自称 34 条）。核实结果：真实核心 11 / 真实但平台级 2 / 部分真实或主张过时 6 / 虚构或夸大 7。GitHub 作为用户 IdP 至今**不是 OIDC Provider**（官方原话不发 ID token；discovery/JWKS/userinfo、phantom token、RFC 8693、downscoping、back-channel logout 全部虚构）；唯一例外是 2026 新增的 MCP 专用 public preview metadata 端点，不算 OIDC 实现。两个关键新变化：PKCE 自 2025-07 起支持（仅 S256、推荐不强制、无 public/confidential 客户端区分）；OAuth App 自 2026-08 起可有过期令牌（新 app 默认开）+ 回调 URL 通配成为每 URL 选项（"禁通配符"主张已过时）。限流真实模型是按用户合并桶（非 client_id+user_id）。建议 v1 必做：passkey+sudo mode、梯级 scope、consent 勾选、redirect 白名单、强制 PKCE（仅 code/device/refresh 三 grant）、opaque token+check/revoke（token 与 grant 双端点）、refresh 单次轮转、授权看板、REST /me、限流头、基础审计。缓：组织安装模型、PAT、webhook、多 secret、secret scanning。弃：全部 OIDC/网关系条目。

详见：`.scratch/jauth-hub/research/02-github-features.md`（逐条对比表 + 来源 URL + 裁剪建议汇总）。
