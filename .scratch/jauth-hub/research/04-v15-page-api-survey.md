# v1.5 前端归一 — JSON API 缺口普查档案（B0 第二半）

- 日期：2026-10-02。普查员产物，B1 派单依据。
- 范围：全部 SSR 模板页（core 3 + selfservice 10 + app 6 = 19 页）的数据来源现状与迁 Vue 的端点缺口。
- 契约基准（v1.4 已定型）：页面态 GET 返回 `ResponseRenderer.renderSuccess` 包装（code/message/data，00000 成功），data 内含 `csrfToken`/`csrfHeaderName` 字段（照 `SudoStateController.SudoState`，jauth-hub-selfservice/.../web/SudoStateController.java:70）。操作 POST 全部已存在且已是 ResponseRenderer 形态——**v1.5 缺口集中在"页面态/列表 GET"，动作面零缺口**。
- 路径缩写：`SS = jauth-hub-selfservice/src/main/java/io/github/oatelauser/jauth/selfservice/web`，`APP = jauth-hub-app/src/main/java/io/github/oatelauser/jauth/app/web`，`CORE = jauth-hub-core/src/main/java/io/github/oatelauser/jauth/core/web`，`SST = jauth-hub-selfservice/src/main/resources/io/github/oatelauser/jauth/selfservice/web/templates`，`AT = jauth-hub-app/src/main/resources/templates`。

## 0. 总览

| # | 页面 | 模块 | 状态 | 缺口端点 |
|---|------|------|------|----------|
| 1 | login | core | 已完成（v1.4） | — |
| 2 | consent | core | 已完成（v1.4） | — |
| 3 | device-verify | core | 已完成（v1.4） | — |
| 4 | sudo | selfservice | 已完成（v1.4） | — |
| 5 | apps（看板） | selfservice | 有缺口 | 1（页面态 GET） |
| 6 | pat | selfservice | 有缺口 | 1（页面态 GET，含 scope 目录） |
| 7 | my-apps | selfservice | 有缺口 | 1（列表+页面态合一 GET） |
| 8 | my-app-new | selfservice | 复用 | 0（并入 my-apps 页面态） |
| 9 | my-orgs | selfservice | 有缺口 | 1（列表+页面态合一 GET） |
| 10 | org-apps | selfservice | 有缺口 | 1（列表+页面态合一 GET） |
| 11 | org-installations | selfservice | 有缺口 | 1（行列表+页面态合一 GET） |
| 12 | org-members | selfservice | 有缺口 | 1（列表+页面态合一 GET） |
| 13 | passkey | selfservice | 有缺口 | 1（凭据列表+页面态合一 GET） |
| 14 | profile | app | 有缺口 | 1（页面态 GET） |
| 15 | admin/users | app | 有缺口 | 1（分页列表 GET 含页面态） |
| 16 | demo/index | app | 有缺口 | 1/4（demoConfig 页面态，四页共用） |
| 17 | demo/token | app | 有缺口 | 同上共用 |
| 18 | demo/api-call | app | 有缺口 | 同上共用 |
| 19 | demo/callback | app | 有缺口 | 同上共用 |

统计：**19 页 = 4 已完成 + 15 有缺口（其中 my-app-new 复用、demo×4 共用），合计 11 个新 GET 端点；操作 POST/DELETE 端点 0 缺口**（v1.1–v1.3 各批已把动作面全部 JSON 化）。

## 1. core（信任面四页，v1.4 已完成）

| 页面 | SSR GET | 页面态 API |
|------|---------|-----------|
| login | `/login` CORE/LoginController.java:50 | GET+POST `/api/login` CORE/LoginApiController.java:62,83 |
| consent | `/oauth2/consent` CORE/ConsentController.java:60 | GET `/api/consent` CORE/ConsentStateController.java:50 |
| device-verify | `/device/verify` CORE/DeviceVerifyController.java:45 | GET `/api/device/verify` CORE/DeviceVerifyStateController.java:37 |
| sudo | `/selfservice/sudo` SS/SudoController.java:74 | GET `/api/sudo` SS/SudoStateController.java:56 |

B3 前端（jauth-hub-front）已消费，无需动作。TrustSkinFlag 的 front 模式 302 已覆盖四页。

## 2. selfservice 页明细

### 2.1 apps（已授权应用看板）
- GET `/selfservice/apps` → SS/AuthorizedAppsController.java:88 `page()`
- 模型属性：`educational`、`appsSupported`、`passkeyEnabled`、`apps`（`AppView`：clientId/clientName/scopes/lastAuthorizedAt，:189）
- 页内 JS（SST/apps.html:69）：POST `/selfservice/apps/{clientId}/revoke`（:121，ResponseRenderer）
- 既有 JSON：GET `/selfservice/apps/list`（:106，ResponseRenderer，返回同形 AppView 列表）
- **缺口清单**：①页面态 GET（educational/appsSupported/passkeyEnabled + csrf；列表可并入或让前端二次调既有 /list）
- i18n：11 keys

### 2.2 pat
- GET `/selfservice/pat` → SS/PatController.java:109
- 模型属性：`educational`、`patSupported`、`scopes`（ScopeItem name+description，**服务端解析 i18n**）、`validityDays`(30/90/365)、`defaultValidityDays`(90)、`pats`（PatRecord）、`now`
- 页内 JS（SST/pat.html:111,123）：POST `/selfservice/pat`（创建，:151）、POST `/selfservice/pat/{id}/revoke`（:187）——均 ResponseRenderer
- 既有 JSON：GET `/selfservice/pat/list`（:131，ResponseRenderer，patView 行）
- **缺口清单**：①页面态 GET（patSupported/scope 目录带 i18n 描述/validity 阶梯/default/now + csrf；照 B3 决议 scope 描述保持 server-resolved）
- i18n：24 keys + 动态 `'jauth.pat.validity-' + days`（grep 未计，+3）

### 2.3 my-apps
- GET `/selfservice/my-apps` → SS/MyAppsController.java:90
- 模型属性：`educational`、`appsSupported`、`apps`（`OwnedApp`：id/clientId/name/confidential/redirectUris/createdAt，OwnedAppService.java:346）
- 页内 JS（SST/my-apps.html:91）：POST `/{id}/secret` 轮转（:164，@RequiresSudo）、POST `/{id}` 编辑（:185）、DELETE `/{id}`（:209）——均 ResponseRenderer；A0515 → 跳 /selfservice/sudo?returnTo=
- 既有 JSON：POST `/selfservice/my-apps` 注册（:122）——ResponseRenderer
- **缺口清单**：①列表+页面态合一 GET（apps 行 + appsSupported/educational + csrf）
- i18n：18 keys

### 2.4 my-app-new
- GET `/selfservice/my-apps/new` → SS/MyAppsController.java:108
- 模型属性：`educational`、`appsSupported`
- 页内 JS（SST/my-app-new.html:75）：POST `/selfservice/my-apps`（同 2.3 注册端点）
- **缺口清单**：无独立端点——复用 2.3 的页面态 GET（Vue 路由可做成 my-apps 的子路由/对话框）

### 2.5 my-orgs
- GET `/selfservice/my-orgs` → SS/MyOrgsController.java:75
- 模型属性：`educational`、`orgsSupported`、`orgs`（`OrgMembership`：orgId/orgName/role，core/org/OrgMembership.java:13；OWNER 行带 installations/apps/members 三个入口链接）
- 页内 JS（SST/my-orgs.html:86）：POST `/selfservice/my-orgs`（创建，:94，ResponseRenderer）
- **缺口清单**：①列表+页面态合一 GET
- i18n：18 keys

### 2.6 org-apps
- GET `/selfservice/orgs/{orgId}/apps` → SS/OrgAppsController.java:101（仅 OWNER，非 OWNER A0508）
- 模型属性：`educational`、`orgAppsSupported`、`org`（Org）、`apps`（OwnedApp 列表）
- 页内 JS（SST/org-apps.html:124,150）：POST 注册（:125）、POST `/{id}/secret`（:186，sudo）、POST `/{id}`（:209）、DELETE `/{id}`（:239）——均 ResponseRenderer
- **缺口清单**：①列表+页面态合一 GET（org 名 + apps 行 + supported + csrf；OWNER 门失败语义 = A0508 业务码）
- i18n：28 keys

### 2.7 org-installations
- GET `/selfservice/orgs/{orgId}/installations` → SS/OrgInstallationsController.java:120（仅 OWNER）
- 模型属性：`educational`、`installationsSupported`、`org`、`pending`（InstallationRow 过滤）、`installations`（全量 InstallationRow：id/clientName/clientLabel/status/ceilingScopes/requestedByName/requestedScopes/approvedByName/approvedAt/createdAt，:362，含服务端拼好的 `statusKey()`）、`scopes`（ScopeItem 目录）
- 页内 JS（SST/org-installations.html:144）：POST request（:152）、approve（:188）、reject（:216）、revoke（:236）——均 ResponseRenderer
- **缺口清单**：①行列表+页面态合一 GET（含 pending/installations 两分区、scope 目录、org；statusKey 可保留服务端拼好或改客户端字典——JSON 面建议直接给 status 枚举名，i18n key 由前端字典持有）
- i18n：25 keys + 动态 status-*（服务端 statusKey，+4）

### 2.8 org-members
- GET `/selfservice/orgs/{orgId}/members` → SS/OrgMembersController.java:72（仅 OWNER）
- 模型属性：`educational`、`membersSupported`、`orgId`、`members`（行：userId/username/role/createdAt，memberRows :158）
- 页内 JS（SST/org-members.html:82）：POST 添加（:94）、DELETE `/{userId}`（:123，sudo）、POST `/{userId}/role`（:144，sudo）——均 ResponseRenderer
- **缺口清单**：①列表+页面态合一 GET
- i18n：15 keys

### 2.9 passkey
- GET `/selfservice/passkey` → SS/PasskeyController.java:70
- 模型属性：`educational`、`passkeyEnabled`、`credentials`（PasskeyCredentialView：credentialId/credentialIdShort/label/createdAt/lastUsedAt，:111）
- 页内 JS（SST/passkey.html:99,139,162）：POST `/webauthn/register/options`、POST `/webauthn/register`、DELETE `/webauthn/register/{credentialId}`——**框架端点**（Spring Security WebAuthn），非 ResponseRenderer 形状，迁移时原样复用（login/sudo 已有同款内联 JS → src/webauthn.js 对齐先例）
- **缺口清单**：①凭据列表+页面态合一 GET（passkeyEnabled/educational + credentials + csrf）
- i18n：19 keys

### 2.10 sudo — 已完成（见 §1），不重查。

## 3. app 页明细

### 3.1 profile
- GET `/profile` → APP/ProfileController.java:82
- 模型属性：`educational`、`username`、`displayName`
- 页内 JS（AT/profile.html:61）：POST `/api/profile`（改显示名，:98）、POST `/api/profile/password`（改密，:123，@RequiresSudo）——均 ResponseRenderer
- **缺口清单**：①页面态 GET（username/displayName/educational + csrf）
- i18n：16 keys

### 3.2 admin/users
- GET `/admin/users` → APP/AdminUsersController.java:107（@RequiresRole SUPER_ADMIN；page/size 参数，默认 20、上限 100）
- 模型属性：`educational`、`users`（当页行）、`pageNum`/`pageSize`/`total`/`totalPage`（**分页元数据已按 PageResponse 契约口径规整**，:113 注释）
- 页内 JS（AT/admin/users.html:106）：POST `/api/admin/users` 建号（:132）、`/{id}/role`（:180，sudo）、`/{id}/status`（:215，sudo）、`/{id}/password`（:253，sudo）——均 ResponseRenderer
- **缺口清单**：①分页列表 GET（users 行 + 分页元数据 + educational + csrf；建议 data 直接套家族 PageResponse 的 item/total/pageNum/pageSize/totalPage 形状）
- i18n：29 keys

### 3.3 demo 四页（index/token/api-call/callback）
- GET `/demo` APP/DemoController.java:55、`/demo/callback` :60、`/demo/token` :65、`/demo/api-call` :70
- 模型属性：`educational` + `demoConfig`（issuer/authorizeEndpoint/tokenEndpoint/introspectEndpoint/clientId(demo-public)/redirectUri/scope/rsClientId(demo-rs)/**rsClientSecret**/whoamiUri，:82）
- 页内 JS：全部走 `static/demo/demo.js`（jauthDemo 助手：PKCE verifier/challenge、state、httplog 面板）+ `th:inline` 注入 CFG；请求目标是 OAuth2 协议端点（authorize 顶层导航、token/introspect fetch）与 GET `/api/demo/whoami`（APP/DemoWhoamiController.java:22，**SimpleResponse.ok 形状**——spring-plus 统一响应，00000 码系但非 jauth ResponseRenderer SPI）
- **缺口清单**：①demoConfig 页面态 GET（四页共用一个端点；无 CSRF 需求——协议端点/公开页）。注意 rsClientSecret 随页面态 JSON 化时保持现状边界（dev 教学夹具，文案已明示生产内省凭证只在资源服务器后端）
- i18n：index 8 / token 15 / api-call 10 / callback 9

## 4. 横向事实

### 4.1 JSON 端点形态统计
- **selfservice 控制器：22 个 JSON 端点，100% ResponseRenderer 形态，0 裸 JSON。** 分布：AuthorizedApps 2（list/revoke）、Pat 3（list/create/revoke）、MyApps 4（register/secret/update/delete）、MyOrgs 1（create）、OrgApps 4、OrgInstallations 4、OrgMembers 3、SudoState 1。
- **app 模块：6 个业务 JSON 全 ResponseRenderer**（profile 2 + admin/users 4）；另有 2 个教学/样例端点为 `SimpleResponse.ok` 形状（`/api/admin/summary` APP/AdminSampleController.java:23、`/api/demo/whoami` APP/DemoWhoamiController.java:22）——spring-plus 家族统一响应，与 jauth SPI 不同类型但同为 00000 码系；迁 Vue 的 demo 页直接消费现状即可，不必改 SPI。
- **GET 型数据端点稀缺是核心缺口形态**：selfservice 仅 3 个 GET（apps/list、pat/list、api/sudo），其余 9 页数据全在 SSR model 里；app 模块 0 个 GET 数据端点。
- 框架端点（非 jauth 渲染、迁移时原样复用）：`/webauthn/register/options`、`/webauthn/register`、`DELETE /webauthn/register/{id}`、`/oauth2/token`、`/introspect`。

### 4.2 CSRF 获取模式
- 12 个带 fetch 的模板（selfservice 全部 10 + profile + admin/users）已有统一先例：`<meta name="_csrf" th:if="${_csrf != null}">` + `meta[name="_csrf_header"]`，JS 读 meta 塞 header。demo 四页无 CSRF（协议端点/公开页）。
- v1.5 迁移后此模式退役：Vue 页从页面态 API 的 `csrfToken`/`csrfHeaderName` 字段取（v1.4 契约，jauth-hub-front 的 fetch wrapper 已实现该消费）。**派生约束：凡有操作 POST 的页面，其页面态 GET 必须带 csrf 字段——上表每个"页面态 GET"缺口都已计入。**
- A0515（sudo 过期）跳转先例：my-apps/org-apps/org-members/org-installations/profile/admin/users 六页 JS 均有 `→ /selfservice/sudo?returnTo=` 处理；Vue 面应把这语义提进 fetch wrapper（front 已有 401 拆分，需补 A0515 分支）。

### 4.3 i18n 迁移面
- 15 个待迁页合计约 290 个静态 message key（§2/§3 逐页数），另有动态 key 两处：pat 的 `validity-{days}`、org-installations 的 `status-{status}`（服务端拼 key，JSON 化后建议改前端字典按枚举名取）。
- scope 描述保持 server-resolved（B3 决议），页面态 GET 里随 scope 目录带出；其余 key 进 jauth-hub-front 的 zh/en 字典（v1.4 chrome dictionary 先例）。

## 5. B1 建议切分（按缺口密度，3 子批）

- **B1a 自助简单态（6 端点，模式与 v1.4 完全同构，照 SudoStateController 抄）**：apps、pat、my-apps（含 my-app-new 复用）、my-orgs、profile。全部"单资源页面态/列表合一 GET"，无 OWNER 门、无分页。
- **B1b org 族 + admin（4 端点，行聚合 + 门控 + 分页）**：org-apps、org-installations、org-members、admin/users。含 clientName 反查/memberRows 组装/分页元数据（PageResponse 口径）/OWNER 门 A0508 语义；installations 最重（两分区 + scope 目录 + status 枚举）。
- **B1c 特殊面（2 端点）**：passkey（凭据列表 GET + 框架 ceremony 端点形状适配，对齐 src/webauthn.js）、demoConfig（四页共用一个端点，教学区退守 /demo zone 的 v1.5 决议落点；rsClientSecret 边界保持）。

排序理由：B1a 密度最低且能先把"页面态 GET + front fetch wrapper + A0515 分支"的通用模式打磨定型，B1b 复用该模式吃下重页面，B1c 两端点互不依赖可并行收尾。

## 6. 普查方法与未验事项

- 只读普查：逐控制器读源码取路由/模型属性/端点形状；逐模板 grep `fetch(`/`@{`/`csrf`/`#{` 取 JS 端点、CSRF 模式、i18n 计数。未运行应用、未发请求验证（纯静态口径）。
- i18n 计数为 `#{key}` 去重个数，动态拼接 key（validity-N、status-*）未计入，已逐页标注。
- `jauth-hub-resourceserver`/`starter` 模块无模板页，不在普查范围。
