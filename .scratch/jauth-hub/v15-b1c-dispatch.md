# v1.5 B1c 派单词:passkey 与 demoConfig 状态 GET(2 端点,B1 收尾)

> 自包含任务书。你只做本文范围内的事;范围外一律停下汇报。**禁止任何 git 操作**(提交权在主会话)。

## 背景与目标

v1.5 B1 第三子批(最后)。B1a(e56d7f2,六端点)与 B1b(6af75c0,org 族三端点)已落地,契约家族沿用:ResponseRenderer 包装、字段恒定在场、CsrfPayload 结尾、路径 = 页面路径加 /api 前缀、OWNER/池门照既有 JSON 端点形态。详情读 `.scratch/jauth-hub/research/04-v15-page-api-survey.md` §2.9(passkey)与 §3.3(demo 四页)。

## 本批 2 端点

| # | 端点 | 数据 |
|---|---|---|
| 1 | GET `/api/selfservice/passkey` | educational、passkeyEnabled(凭据仓储在场 = starter passkey 开,照 PasskeyController 门控)、credentials(PasskeyCredentialView 行:credentialId/credentialIdShort/label/createdAt/lastUsedAt,**行装配从 PasskeyController 提取包内 statics 复用,不复制**)、csrf 对 |
| 2 | GET `/api/demo/config` | demoConfig 四页共用(issuer/authorizeEndpoint/tokenEndpoint/introspectEndpoint/clientId/redirectUri/scope/rsClientId/rsClientSecret/whoamiUri,装配从 DemoController 提取复用)+ educational;**无 csrf 对**(demo 公开页,页内操作走协议端点无 CSRF 需求)——此为契约家族的有意例外,javadoc 写明 |

**边界提醒(照 survey §3.3)**:rsClientSecret 随页面态 JSON 化保持现状边界——它是 dev 教学夹具,文案已明示生产内省凭证只在资源服务器后端,不因 JSON 化扩大暴露口径(javadoc 引用该口径)。

**passkey 框架 ceremony 端点不动**:/webauthn/register/options、/webauthn/register、DELETE /webauthn/register/{credentialId} 由前端原样复用(jauth-hub-front/src/webauthn.js 对齐先例),本批只做凭据**列表**状态面。

## 实现要点

- `PasskeyStateController` 放 selfservice/web,bean 注册进 `JauthSelfServiceAutoConfiguration.PageConfiguration`(照 B1a/B1b 形态,PasskeyFlag/UserCredentialRepository 经 ObjectProvider 可缺省);`DemoConfigStateController` 放 app/web(组件扫描,照 B1a app 面先例)。
- 载荷 record 不可变(List.copyOf)。

## 测试

1. passkey 契约:renderer 形状 + 全字段 + csrf 对;仓储缺席 → passkeyEnabled=false + credentials 空列表(不 500);行装配回归与提取 statics 同形。
2. demoConfig 契约:renderer 形状 + demoConfig 全字段 + educational,**无 csrf 字段**(断言缺席);装配与 DemoController 同源同值。

## 约束

- **禁做**:git 操作;**版本号改动(全仓保持 1.4.0)**;新增依赖;动 starter 协议链与框架 webauthn 端点;改 SSR 页行为;动 v1.4/B1a/B1b 已发端点;动 jauth-hub-front/。
- 代码风格:AGENTS.md(javadoc 中文、@author oatelauser、Rationale 注释、Clean Code、p3c);hook 自动格式化+评审,发现当场修。
- 自测:mvn -B verify 全绿。

## 汇报格式(≤15 行)

1. 结论一句话;2. 变更文件清单;3. 新增测试数与总数;4. mvn verify 结果行;5. 偏差与理由(如有);6. 临时文件清单(无则写"无")。
