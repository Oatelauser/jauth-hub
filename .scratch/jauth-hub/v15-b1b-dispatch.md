# v1.5 B1b 派单词:org 族页面态 GET(3 端点)

> 自包含任务书。你只做本文范围内的事;范围外一律停下汇报。**禁止任何 git 操作**(提交权在主会话)。

## 背景与目标

v1.5 前端归一 B1 第二子批。B1a(已落地,commit e56d7f2)定下的模式直接沿用:状态面 @RestController 照 `SudoStateController`/B1a 的 `AppsStateController` 形态、ResponseRenderer 包装、**字段恒定在场**(SSR"属性缺席"语义收敛为空列表/常驻字段)、CsrfPayload 结尾、路径 = 页面路径加 /api 前缀。缺口详情读两份档案:
- `.scratch/jauth-hub/research/04-v15-page-api-survey.md`(B0 普查,§2.6/§2.7/§2.8 是本批三页)
- `.scratch/jauth-hub/v15-b1a-dispatch.md`(B1a 任务书,契约基准节)

## 本批 3 端点

| # | 端点 | 数据(survey §2.6–2.8) |
|---|---|---|
| 1 | GET `/api/selfservice/orgs/{orgId}/apps` | educational、orgAppsSupported、org(Org:orgId/orgName)、apps(OwnedApp 行——**行装配复用 B1a/既有 MyApps 提取的同源 statics,不复制**)、csrf 对 |
| 2 | GET `/api/selfservice/orgs/{orgId}/installations` | educational、installationsSupported、org、**pending 与 installations 两分区**(InstallationRow 行,见下)、scopes(ScopeItem 带 i18n 描述,服务端解析)、csrf 对 |
| 3 | GET `/api/selfservice/orgs/{orgId}/members` | educational、membersSupported、org(orgId/orgName)、members(userId/username/role/createdAt 行,复用既有 memberRows 装配)、csrf 对 |

**InstallationRow 的 JSON 形态(与 SSR 的差异,有意为之)**:status 字段返回**枚举名**(PENDING/REJECTED/APPROVED/REVOKED 原名),不返回服务端拼好的 statusKey——i18n key 归前端字典(survey §4.3 决议);其余字段(id/clientName/clientLabel/ceilingScopes/requestedByName/requestedScopes/approvedByName/approvedAt/createdAt)照 SSR 行装配复用提取。

## 门控与错误语义(照既有 JSON 操作端点先例,不发明)

- OWNER 门在 OrgService 单点(v1.3 D3:A0508,org 存在性不泄露)——三端点同门,失败形态照本控制器族**既有 JSON 操作端点的 A0508 返回形态**(自行读 OrgAppsController/OrgInstallationsController/OrgMembersController 的 JSON 方法对齐,含 HTTP 状态位)。
- 依赖缺席降级态:supported=false 常驻字段照 B1a(appsSupported 先例),不 500。

## 实现要点

- 新控制器 `OrgAppsStateController`/`OrgInstallationsStateController`/`OrgMembersStateController` 放 selfservice/web 同包;SSR 控制器的行装配私有方法需要跨类复用时照 B1a 提取包内 statics(带参),**不复制**。
- bean 注册:`JauthSelfServiceAutoConfiguration.PageConfiguration` 照 B1a 四 bean 形态。
- 载荷 record 不可变(集合 List.copyOf)。

## 测试(每端点至少)

1. JSON 契约:renderer 形状 + data 全字段 + csrf 对;installations 断言 status 为枚举名。
2. OWNER 门:非 OWNER/非成员 → A0508 形态照既有 JSON 端点;org 不存在 → 同形不泄露。
3. 行装配回归:与 SSR 模型断言等价(同数据源同形状)。
4. 降级态:依赖缺席 → supported=false 不 500。

## 约束

- **禁做**:git 操作;版本号改动;新增依赖;动 starter 协议链;改 SSR 页行为;动 v1.4 四端点与 B1a 六端点;动 jauth-hub-front/。
- 代码风格:AGENTS.md(javadoc 中文、@author oatelauser、Rationale 注释、Clean Code、p3c);hook 自动格式化+评审,发现当场修。
- 自测:mvn -B verify 全绿。

## 汇报格式(≤20 行)

1. 结论一句话;2. 变更文件清单;3. 新增测试数与总数;4. mvn verify 结果行;5. 偏差与理由(如有);6. 临时文件清单(无则写"无")。
