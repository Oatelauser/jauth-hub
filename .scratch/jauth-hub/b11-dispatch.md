# B11 派单词:我的组织页 + 安装审批页 + org 应用注册(selfservice + core 小补)

> 你是 B11 批次的实施 subagent。本文自包含,读完直接开工。**禁止任何 git 操作**;越界(动 app 模块、改 pom 门禁、调测试阈值)即停并汇报。

## 一、决议摘录

1. **org 创建 = 自助**(2026-09-30 拍板):任何登录用户可建 org,创建者自动 OWNER;"我的组织"页随 B11——即本批。
2. **安装流向 A 两步制**(拍板):任何登录用户发起安装请求(PENDING,记 requested_by/requested_scopes)→ org OWNER 审批勾 ceiling_scopes 生效/驳回;APPROVED 可撤销。B8 域服务全在,本批只做**页面/接口面**。
3. **org 应用注册**(B10 记档移入本批):org 应用 = `ClientOwner.ofOrg(orgId)`,注册操作者须为该 org OWNER;构造惯例与个人应用同(ClientSeeder 系)。
4. UI:Thymeleaf SSR + 手写 CSS、zh+en i18n、零依赖;响应走 ResponseRenderer/JauthException;错误码 selfservice 从 A0511 续段(段位账见 `SelfServiceErrorCode`/`JauthErrorCode` javadoc,增补须同步账面)。

## 二、主会话已核实的事实(直接用)

- **路由纪律(B10 血泪)**:新路由必须先 grep 既有映射防撞。现占用:`/selfservice/pat*`、`/selfservice/apps`(已授权页!)、`/selfservice/my-apps*`。本批用 **`/selfservice/my-orgs*`** 与 **`/selfservice/orgs/{orgId}/...`**,已确认无撞。app 放行清单是 `/selfservice/**`,前缀对了 app 侧零改动。
- B8 域服务直接调:`OrgService.create/findByUser/isOwner`(事务已收口)、`InstallationService.request/approve/reject/revoke`(OWNER 门+ceiling⊆requested+A0506/07/08 全在服务层,页面不重复判)。
- `UserRepository.findById` 在(审批页显示发起人/成员名用)。
- B10 的 `OwnedAppService` 三件套是本批页面的直系模板(抽象基类+Jdbc/InMemory+Controller+模板+契约测试全套惯例);机密 secret 一次性、redirect 校验单源、UUIDv7/`app_` 前缀等构造惯例全部复用。
- 审计事件 B8 全在场(org.created/installation.*),页面经服务调用自然落审计,不加新事件。
- `InstallationRepository` 现有 findById/findByClientAndOrg/findByClient(双实现)——**缺 findByOrg**,本批补。

## 三、改动清单

### 1. 我的组织页

- `GET /selfservice/my-orgs`:当前用户归属 org 列表(OrgService.findByUser,含角色徽标)+ "新建组织"表单(名称,唯一性冲突 A0506 由服务层抛,页面显示错误态)。
- `POST /selfservice/my-orgs`(JSON,照 my-apps 惯例):OrgService.create,成功回跳列表。
- 每行 org 提供入口:该 org 的安装审批页(仅 OWNER 角色显示入口;MEMBER 不显示)。

### 2. 安装审批页(OWNER 面)

- `GET /selfservice/orgs/{orgId}/installations`:非该 org OWNER → A0508(服务层语义);页面分区:PENDING 待批(发起人名经 `UserRepository.findById` 反查、requested_scopes 列表)、本 org 全量安装行(状态徽标 PENDING/APPROVED/REJECTED/REVOKED、ceiling、审批人/时间)。
- **批准交互**:每条 PENDING 行内表单——requested_scopes 渲染为 checkbox(默认全选,OWNER 可收窄勾选为 ceiling)→ `POST /selfservice/orgs/{orgId}/installations/{id}/approve`(勾选集即 ceilingScopes,⊆ requested 由服务层强制)。
- `POST .../reject`、`POST .../revoke`(APPROVED 行显示撤销钮)。空勾选=拒绝?否——空勾选提交按 A0502 走(服务层 ceilingScopes 可传空集?**不允许**:控制器先校验勾选非空再调服务,空集给 A0511 新码"ceiling 勾选为空"或复用 A0505 风格自立,按段位账定)。
- "发起安装"入口:该页提供**面向 org 的安装发起表单**(client 选择=平台可安装客户端列表?**最小面不做浏览目录**——表单手填 client_id + 勾选 scope 目录全集),`POST /selfservice/orgs/{orgId}/installations`(InstallationService.request,requestedBy=当前用户)。装进哪个 org 就是路径里的 orgId(发起人不要求是该 org 成员,拍板原文)。

### 3. org 应用注册

- **泛化而非另起**:`OwnedAppService` 增 org 路径——`registerOrg(orgId, name, redirectUris, confidential)`(构造同个人应用,owner=ofOrg);`listOrg(orgId)`。实现建议:基类 `register(...)` 收敛到 `register(ClientOwner owner, ...)` 私有核 + 两个公开门面;Jdbc list 加 `owner_org_id = ?` 查询;InMemory 索引表按 owner 维度扩展。**B10 既有个人面 API 与行为不得变**(契约测试存量全绿为证)。
- `GET /selfservice/orgs/{orgId}/apps` + `POST .../apps`:org 应用注册/列表,OWNER 门同上;复用 my-app-new 模板结构另立 org 版(或模板条件化,取小 diff)。
- `ClientOwnerResolver` 语义自动生效:jdbc 写 owner_org_id 列;memory 走 put。

### 4. core 小补

- `InstallationRepository.findByOrg(String orgId)`(双实现;注意 B9 findByClient 的既定惯例——单查询口径,javadoc 写明消费者是审批页)。

### 5. 测试

- 页面 DOM 测试三套(我的组织/审批页三态/org 应用页,照 MyAppsControllerTest 模式,principal 注入)
- findByOrg 双实现契约;org 应用注册契约(OWNER 外拒、列表隔离:org 应用不出现在个人列表、反之亦然)
- **旗舰 E2E 一条**(照 CeilingEnforcementIntegrationTest):注册 org 应用(服务面)→ 用户 A(org 成员)request 安装 → OWNER approve 收窄 ceiling → A 走 consent → 换 token → scope = 交集。B9 的取交防线在此闭环验收。
- 审批页越权(MEMBER/局外人)A0508;空 ceiling 勾选拒。

### 明确不做(记档)

成员管理面(邀请/移除/转让——仍无页面语义定案,v1.2 候选);安装浏览目录/市场页;删除/编辑应用(随 B10 记档);审计新事件;迁移脚本(零)。版本不动。

## 四、开工前先读

`AGENTS.md`;B10 全套(`MyAppsController`/`OwnedAppService` 三件套/两模板/`MyAppsControllerTest`——直系模板);`core/org` B8 包(服务语义);`OrgScopeGate`(口径);`PatController` 的表单+CSRF+JSON 惯例;`SelfServiceErrorCode`+`JauthErrorCode`(段位账);`JauthSelfServiceAutoConfiguration`(viewNames 白名单要加新视图名!B10 惯例);i18n 两个 properties。

## 五、验收标准

1. `mvn verify` 全绿(296 存量 + 新增),三门禁过,零豁免
2. 旗舰 E2E 过:org 应用 → 安装 → 审批收窄 → consent → 令牌 scope = 请求∩consent∩ceiling
3. OWNER 门全在服务层,页面只做入口控制;越权 A0508 有测试
4. B10 个人面行为零变(存量契约测试不动照绿)
5. 路由零撞(apple 集成测试会兜底);viewNames 白名单含新视图
6. hook 自动 spotless 属正常;非格式化自动改动停手汇报;评审提醒不管、不跑 git

## 六、汇报(≤40 行)

改动文件清单(新建/修改分列)、新增测试数、`mvn verify` 尾行证据、遗留/取舍。临时产物不留。
