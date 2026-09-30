# B9 派单词:ceiling 取交 + consent org 选择器 + orgs 富化(core + starter)

> 你是 B9 批次的实施 subagent。本文自包含,读完直接开工。**禁止任何 git 操作**;越界(动 selfservice/app 页面、改 pom 门禁、调测试阈值)即停并汇报。

## 一、决议摘录(SPEC §3,实施宪法)

1. **发行 scopes = 请求 ∩ consent ∩ installation.ceiling,运行时取交**;**多 org 歧义在 consent 页选上下文;个人应用无此步**(个人/平台客户端不装、不封顶)。
2. **内省富化**:`/introspect` 响应与 OIDC claims 走同一映射扩展点(`ClaimsContributor`,core/token),默认带 sub/username/scope/**orgs(含角色)**。接口 javadoc 原文:"orgs(含 org 角色)属 v1.1 平台层……在本 SPI 上追加实现,接口形状无需变更(上下文已带 principal/client/scope 三要素)"。
3. 词表归代码枚举;UUIDv7;双库兼容;错误码续用 A0506-A0508/B0502(不足再按段增补,JauthErrorCode javadoc 有段位账)。

## 二、主会话已核实的接线事实(直接用,不要重新推导)

- **授权服务链**(JauthHubAutoConfiguration):memory = `Auditing(FamilyAware(InMemoryOAuth2AuthorizationService))`(~L720);jdbc = `Auditing(PatAware(JauthJdbc(...)))`(~L854)。
- **owner 运行时读取只有 Jdbc 通道**:`JauthJdbcRegisteredClientRepository.findOwnerById(id) → ClientOwner`;**memory 模式无 owner 存储**(ClientSeeder 不记 owner)——本批要补双模式通道(见下)。`ClientOwner` 语义:userId/orgId 至多一非空,皆空=平台内置。
- **consent 面**:`ConsentController` 纯 GET 渲染(`/oauth2/consent`),表单 POST 回 `/oauth2/authorize`(bfeded3 契约,勿破);框架在 POST 上收 client_id/state/scope 下发授权码。scopes 复选框当前恒 checked。
- **令牌侧**:两个 `OAuth2TokenCustomizer`(`OpaqueAccessTokenCustomizer`/`OidcIdTokenCustomizer`)吃 `List<ClaimsContributor>`——orgs 贡献者加入该列表即同时覆盖 opaque 内省 / id_token / userinfo。
- B8 产物可直接用:`InstallationRepository.findByClientAndOrg`、`OrgRepository.findMembershipsByUser`(返回 `OrgMembership(orgId,orgName,role)`)、`InstallationService` 状态机; Installation 行有 `ceilingScopes`。

## 三、改动清单

### 1. ceiling 取交强制(服务端,防线核心)

- 新建 `core/authorization/CeilingAwareOAuth2AuthorizationService`(装饰器,包 `OAuth2AuthorizationService`):
  - **仅创建时保存**生效(`authorization.getAccessToken().isEmpty()` 且 `authorizedScopes` 非空——覆盖授权码与设备码两种创建,刷新/换令牌的后续保存直通;幂等:authorizedScopes 已 ⊆ ceiling 就不动)
  - owner 解析:平台(`ClientOwner.platform()`)/个人(userId 非空)→ 直通不剪;组织(orgId 非空)→ 走剪枝:
    - principal name → `UserRepository.findByUsername` → 用户 id → `findMembershipsByUser` ∩ 该 client 的 **APPROVED** 安装 → 候选 org 集
    - **0 个 → 抛异常 fail-closed**(组织客户端必须先安装;consent 页渲染层本就拦,这是防手造 POST 的纵深防御);**1 个 → 用之**;**>1 → 查会话暂存(下述 3)取所选 org**,无暂存也抛(多 org 静默重授权 v1.1 不支持,注释写明 ceiling 与升级路径)
    - 剪:`authorizedScopes ∩ installation.ceilingScopes`;有变化才重建(`OAuth2Authorization.from(x).authorizedScopes(...)`)再委托保存
  - 装配:装饰在**链最内层紧贴 base**(memory:`Auditing(FamilyAware(Ceiling(base)))`;jdbc:`Auditing(PatAware(Ceiling(JauthJdbc(...))))`)——外层审计/PAT/家族必须观察到剪后状态
- 新建 `core/client/ClientOwnerResolver` 接口(`findOwner(registeredClientId) → @Nullable ClientOwner`)+ 双实现:Jdbc 委托现有 `findOwnerById`;InMemory = ConcurrentHashMap + `put` 暴露给 seeder/测试(memory 模式自此能表达组织客户端)。starter 两段 storage 各注 bean。

### 2. consent 页 org 选择器(UX)

- `ConsentController` 增强(构造器加:Authentication 取 principal、`UserRepository`、`ClientOwnerResolver`、`OrgRepository`、`InstallationRepository`、`HttpSession`):
  - 个人/平台客户端 → 渲染完全不变(回归保障)
  - 组织客户端:算候选 org 集(同上口径)——**0 个** → 渲染引导态(提示需 org OWNER 安装,文案进 i18n,zh+en 骨架);**1 个** → 隐式上下文 + 暂存 + 页面徽标展示;**>1** → 选择器(GET 带 `org` 参数重入本页,链接式切换,保留原有 client_id/state/scope 参数;合法选择 → 会话暂存 + 重渲染)
  - **会话暂存**:`session["jauth.consent.org::" + state] = orgId`(GET 时写;Ceiling 装饰器经 `RequestContextHolder.currentRequestAttributes()` 读——授权码/设备码创建保存都发生在请求线程,成立)
  - 选定 org 后:ceiling∩requested 内的 scope 正常勾选;requested 但超出 ceiling 的**展示但禁用**(教学面:看得见批不下来);ScopeItem 增加 grantable 字段,consent.html 相应渲染 + 选择器/徽标/引导态区块(沿用现有 CSS 语言,零新依赖)
- 渲染层只是 UX,强制在 1(服务端装饰器)——两者口径注释互指

### 3. orgs 富化

- 新建 `core/org/OrgsClaimsContributor implements ClaimsContributor`:principal → memberships → claim `orgs` = `List<Map<String,String>>`(每项 `id`/`name`/`role`;id 供业务鉴权、name 供展示);查无用户返回空 Map。注册为 bean 并加入两个 customizer 的 contributors 列(照 `DefaultClaimsContributor` 的装配位置)。
- PAT 侧 orgs(PatIntrospectionSupport)本批**不做**,汇报里注明留 B10+。

### 4. 测试(全绿底线)

- CeilingAware 单测:组织客户端剪枝(personal/platform 直通、0 候选 fail-closed、>1 无暂存 fail-closed、>1 有暂存按暂存剪、幂等、有 accessToken 的保存直通)
- **端到端至少一条**:starter 真实链(memory 或 jdbc)——组织客户端 + ceiling ⊂ 请求 + consent 提交超集 → 换得的 access token scope = 交集;personal 客户端既有 E2E 回归不变绿
- 存量 consent 静默路径 + ceiling 事后收缩 → 静默再授权也被剪(防绕过,必须有)
- ConsentController 渲染:1 org 隐式/2 org 选择器/0 org 引导/禁用 scope 项(照 ProtocolPagesTest 的 DOM 断言风格)
- orgs claim:id_token、opaque claims(内省口径)各自断言形状
- ClientOwnerResolver 双实现契约测试(照 B8 contract 模式)

### 明确不做

selfservice/app 任何页面;安装审批/发起面(B11);`jauth_pat` 名列 V7(B10);PAT orgs;迁移脚本(零——V6 列已够);版本号不动。ConsentController 的"已授权默认勾选"(存量 consent 预勾)若实现代价小可顺带,代价大明确留 B10+ 并汇报。

## 四、开工前先读

`AGENTS.md`;`core/authorization/`(Auditing/FamilyAware/JauthJdbc 三个授权服务——装饰器风格);`web/ConsentController.java` + `templates/consent.html` + `ProtocolPagesTest`;`token/ClaimsContributor` + `DefaultClaimsContributor` + 两个 customizer + `ClaimsTokenCustomizerTest`;`client/ClientOwner` + `JauthJdbcRegisteredClientRepository.findOwnerById` + `ClientSeeder`;`org/` B8 全包;`JauthHubAutoConfiguration` 两段 storage(L700-760、L740-930)。

## 五、验收标准

1. `mvn verify` 全绿(242 存量 + 新增),三门禁过,零豁免
2. 服务端强制与 SPEC 语义一致:个人/平台不剪;组织 = 请求∩consent∩ceiling;0 候选/多 org 无选择 fail-closed
3. 审计看到的发行 scope 是剪后值(链序证据:单测断言 Auditing 层观察值)
4. consent 页三态(隐式/选择器/引导)DOM 断言齐;POST 回 /oauth2/authorize 契约未破
5. orgs claim 三口径(id_token/userinfo/opaque 内省)同形
6. 回合末 hook 自动 spotless 属正常;发现非格式化自动改动立即停手汇报;评审提醒不用管、不跑 git

## 六、汇报(≤40 行)

改动文件清单(新建/修改分列)、新增测试数、`mvn verify` 尾行证据、遗留/取舍(PAT orgs、consent 预勾等)。临时产物不留。
