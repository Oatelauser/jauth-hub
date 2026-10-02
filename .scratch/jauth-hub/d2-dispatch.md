# D2 派单:应用管理级联补全 + secret 轮转 + sensitive 联动 sudo(v1.3;主会话代执行版)

> 配额 kills subagent 期间由主会话照单执行;配额恢复后本单可直接移交 subagent。

## 语义(拍板口径 + 本批裁定)

1. **应用删除**(个人 + org 应用):删 `oauth2_registered_client` 行 + 级联删该 client 全部 `oauth2_authorization` 行、烧断其全部 `jauth_token_family`(跨主体按 client)、删 `oauth2_consent`、删 `jauth_installation` 相关行。轮转不焚令牌;删除全焚。
2. **应用编辑**:改名 + 改 redirect URIs(解析/校验照 register 的 `parseRedirectUris` 口径,精确匹配语义不变),secret 不动。
3. **secret 轮转**:服务端生成新 secret(编码照 register),旧 secret 即刻失效,**不焚令牌**(拍板);响应一次性回显明文(照 register 的 UX 纪律:明文只在创建/轮转响应出现一次)。
4. **新3 裁定(偏离记档)**:轮转端点挂 **@RequiresSudo**(C3 家族);不挂 @RequiresScope——C4 拦截器按 authorities 判,登录会话只有角色无 scope,挂了页面自己进不去。`apps:manage` scope(sensitive=true)注册进 ScopeCatalog 作为未来 API 面;首个真正消费端出现即补挂。
5. **新6 sensitive→sudo 联动**:selfservice 新 `SensitiveScopeSudoInterceptor`——方法有 `@RequiresScope(sensitive=true)` 且 sudo 开时过 SudoGate(fail-closed,阻断形态与 SudoInterceptor 全同:A0515 JSON + SUDO_REQUIRED 审计);随 sudo.enabled 条件注册。当下零生产消费端(设计如此),单测用 dummy HandlerMethod 钉行为。

## 已核实接线事实

- `OwnedAppService`(selfservice/web,抽象基类):`register/registerOrg`(persist 抽象 + ClientOwner)/`list/listOrg`;`OwnedApp` record(id/clientId/name/confidential/redirectUris/issuedAt);`Registration(app, plaintextSecret)`;`parseRedirectUris` 校验口径在此类
- 实现:`JdbcOwnedAppService`(clientSaveWithOwner 函数捕获 + 可选 TransactionOperations)/`InMemoryOwnedAppService`;JDBC 列:owner_user_id/owner_org_id 二选一
- `MyAppsController`:GET `/selfservice/my-apps`、`/new`、POST `/selfservice/my-apps`(register JSON,RegisterRequest);页面模板 my-apps.html(63 行)/my-app-new.html(98 行)
- `OrgAppsController`:/selfservice/orgs/{orgId}/apps(org 应用面,共享 OwnedAppService)
- `JdbcTokenFamilyService.burnFamily(principal, client)` 已有;按 client 跨主体烧断本批加 `burnAllByClient(registeredClientId)`(内存版镜像;内存授权对象不级联删——memory 模式重启消亡既定语义,javadoc 记)
- `SudoInterceptor`(selfservice/web):SudoGate.isFresh(username) 判定、阻断=JauthException(A0515)+SUDO_REQUIRED 审计(detail=path)、resolveUserId 反查——SensitiveScopeSudoInterceptor 照抄此形态
- `RequiresScopeInterceptor`(core/web):注册于 starter `jauthRequiresScopeInterceptorConfigurer`(无依赖 new);scope 目录注册经 `jauthRequiresScopeRegistrar`
- D1 珊珊来迟的接线:PrincipalAuthorizationRevoker(本批不动——按 client 轴是 OwnedAppService 自己的级联,不塞进 principal 接口)
- 既有审计事件词表:ORG_CREATED/INSTALL_* 等;本批删应用打 `CLIENT_DELETED`?→ 用现有词表缺口:**新增 AuditEventType `CLIENT_DELETED("client.deleted")`**(生命周期事件,票 07 词表语义内)
- i18n:messages.properties(根=zh)+ messages_en.properties;README 双语归 D6

## 实施清单

1. core `token`:两族谱类加 `burnAllByClient(String registeredClientId)`(镜像+javadoc 互引)
2. core `audit`:`CLIENT_DELETED` 事件类型
3. selfservice `web/OwnedAppService`:加 `rotateSecret(userId|org 门径, appId)`(返 Registration 形态一次性明文)、`update(userId/org, appId, name, redirectUris)`、`delete(userId/org, appId)`——所有权口径与 list 一致(owner_user_id/owner_org_id WHERE 收紧);JDBC 实现里删除级联 = 4 条 SQL(authorization/family烧/consent/installation)+ client 行删;memory 实现 = 内存删 + 族谱烧断
4. selfservice `web/MyAppsController` + `OrgAppsController`:POST `.../{id}/secret`(@RequiresSudo)、PUT/POST `.../{id}`(编辑)、DELETE `.../{id}`(删除,挂 @RequiresSudo——销毁性操作);JSON 面 + 所有权 403(B0502 口径照 PatController)
5. selfservice 新 `SensitiveScopeSudoInterceptor` + 注册(照 SudoInterceptor 的注册点,sudo 开时)
6. 页面:my-apps.html/org-apps.html 加 轮转/删除/编辑 交互(内联 JS 照既有 fetch+confirm 形态;轮转结果一次性弹显);i18n zh/en 词条
7. 测试:OwnedAppService 契约(Abstract 化照 Pat 契约先例?现有 MyAppsControllerTest/OrgAppsControllerTest 扩)+ 级联断言(JDBC:真库删后 4 表零行、他人行不动)+ SensitiveScopeSudoInterceptor 单测(dummy HandlerMethod:敏感+fresh/stale/非敏感/无注解四态)+ 轮转 sudo 门禁 app 集成(SudoGating 再加一例或 selfservice 上下文)

## 禁做

- 不动协议端点/rs-starter/examples;不建 migration(4 表全在);不动 D1 的 PrincipalAuthorizationRevoker;README 归 D6;git 禁止(subagent 执行时)

## 验收

- `mvn verify` 全绿,测试数 ≥433 只增;汇报 ≤20 行:文件清单、verify 证据、新3 裁定重申、临时文件
