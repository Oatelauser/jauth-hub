# v1.5 B1a 派单词:自助面简单页面态 GET(6 端点)

> 自包含任务书。你只做本文范围内的事;范围外一律停下汇报。**禁止任何 git 操作**(提交权在主会话)。

## 背景与目标

v1.5 前端归一:全部 SSR 页迁入 jauth-hub-front,SSR 皮最终拆除(B5)。本批 = B1 三子批之首:**6 个自助/app 简单页面态 GET**,模式与 v1.4 交付的四页状态面完全同构。缺口详情见 `.scratch/jauth-hub/research/04-v15-page-api-survey.md`(B0 普查档案,含每页控制器 file:line 与模型属性清单)——**先读它**,本派单词只钉契约与规则。

## 契约基准(v1.4 已定型,照抄不发明)

- 页面态 GET:`ResponseRenderer.renderSuccess` 包装(code/message/data,00000),data 末尾带 `csrfToken`/`csrfHeaderName`(`CsrfPayload.from(request)`,core.web.CsrfPayload 复用,可空语义见其 javadoc)。
- 形态先例:`SudoStateController`(jauth-hub-selfservice/.../web/SudoStateController.java)——@RestController + produces JSON + 构造注入 ResponseRenderer,载荷 record 字段名即前端契约。
- **路径族规约(本批钉死,B1b/B1c 沿用)**:页面态 API = 页面路径加 `/api` 前缀——selfservice 面 `/api/selfservice/<页>`,app 面 `/api/profile`、`/api/admin/users`、`/api/demo/config`。信任面 v1.4 已发的四端点(/api/login、/api/consent、/api/device/verify、/api/sudo)不迁移不改名。
- 枚举/状态字段返回**枚举名**(如 role=OWNER),i18n 文案归前端字典;唯一例外:scope 描述保持服务端解析(B3 决议)。
- 认证边界:新端点与同名 SSR 页同链同规则(selfservice/app 面归部署方 default 链,starter 协议链**不动**)。
- SSR 页本身零行为变化(B5 才拆);本批只**新增**端点。

## 本批 6 端点(数据构成照普查档案 §2.1–2.5、§3.1)

| # | 端点 | 数据(survey 明细) |
|---|---|---|
| 1 | GET `/api/selfservice/apps` | educational、appsSupported、passkeyEnabled、apps(AppView 行,**复用既有 list 的行装配,别复制粘贴**)、csrf 对 |
| 2 | GET `/api/selfservice/pat` | educational、patSupported、scopes(ScopeItem 带 i18n 描述)、validityDays 阶梯、defaultValidityDays、pats(行装配同既有 /list)、now、csrf 对 |
| 3 | GET `/api/selfservice/my-apps` | educational、appsSupported、apps(OwnedApp 行,行装配从 SSR page() 的私有逻辑提取复用)、csrf 对——my-app-new 页复用此端点,不做独立端点 |
| 4 | GET `/api/selfservice/my-orgs` | educational、orgsSupported、orgs(OrgMembership:orgId/orgName/role)、csrf 对 |
| 5 | GET `/api/profile` | educational、username、displayName、csrf 对 |
| 6 | GET `/api/admin/users` | educational、users(当页行)、分页元数据(**data 直接套家族 PageResponse 形状 item/total/pageNum/pageSize/totalPage**——分页参数与 SSR 同:page/size,默认 20 上限 100,clamp 逻辑复用现控制器)、csrf 对;SUPER_ADMIN 门语义照 SSR(@RequiresRole 在页面 GET 上——新端点同门,实现方式照 app 模块既有形态自行对齐,不削弱) |

## 实现要点

- 新控制器放各页同包(selfservice/web 或 app/web),命名 `<Page>StateController`;既有 SSR 控制器的行装配私有方法如需跨类复用,提取到意图命名的包内协作类或把方法放宽为 package-private——**不复制行装配逻辑**(v1.3 OwnedAppService statics 先例)。
- bean 注册:selfservice 控制器在 `JauthSelfServiceAutoConfiguration.PageConfiguration` 照 jauthSudoStateController 形态注册;app 控制器照 app 模块既有 @Bean/组件形态(自行定位 ProfileController/AdminUsersController 的注册点)。
- 载荷 record 一律不可变(集合 List.copyOf,SpotBugs EI_EXPOSE_REP 防御性拷贝先例:SudoState record 无集合,PatRecord/ConsentState 有)。

## 测试(每端点至少)

1. JSON 契约:renderer 形状(code=00000)+ data 全字段断言 + csrf 字段在场。
2. 行装配回归:与既有 /list(SS 端点)或 SSR 模型断言等价(同数据源出同形状)。
3. 门控:profile/admin 端点的未认证/非 SUPER_ADMIN 拒绝形态照 app 既有集成测试形态;selfservice 端点至少 standalone 契约 + 依赖缺席降级态(appsSupported=false 等,照 ObjectProvider 先例)。
4. 分页(仅 #6):page/size 规整、clamp、PageResponse 形状字段齐。

## 约束

- **禁做**:git 操作;版本号改动(1.3.0 不动,v1.5 收口才升);新增依赖;动 starter 协议链;改 SSR 页行为/模板;动 v1.4 四端点;动 jauth-hub-front/(B2 范围)。
- 代码风格:AGENTS.md(javadoc 中文、@author oatelauser、Rationale 注释、Clean Code、p3c);编辑期 hook 自动格式化+评审,发现当场修。
- 自测:mvn -B verify 全绿(BUILD SUCCESS)。

## 汇报格式(≤20 行)

1. 结论一句话
2. 变更文件清单
3. 新增测试数与测试总数
4. mvn verify 结果行
5. 与普查档案/派单词的偏差(如有,带理由)
6. 临时文件清单(无则写"无")
