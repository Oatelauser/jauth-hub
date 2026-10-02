# v1.4 B1 派单词:信任面三页(consent/device/sudo)JSON 状态 API

> 自包含任务书。你只做本文范围内的事;范围外一律停下汇报。**禁止任何 git 操作**(add/commit/push 都不行,提交权在主会话)。

## 背景与目标

jauth-hub v1.4 里程碑:信任面四页(登录/consent/设备/sudo)JSON API 化,SSR 皮保留默认,混合用法成立(SPEC §1 前端行、issues/10)。本批 = 其中三页的**页面状态 GET API**;登录页(含 POST 认证桥)是 B2,勿做。

## SPEC 摘录(仅相关)

- 端点三分(SPEC §4):管理/自助类接口 → `ResponseRenderer` SPI 统一响应(code/message/data,成功码 00000,错误码 A05xx 家族)。
- 混合用法成立:同一部署可四页全 SSR、全 headless、或逐页混搭;headless 皮调 JSON API,不自带 UI 的页落回内置 SSR 皮。
- 运行时零外链(SPEC §1):页面永不引第三方 CDN——本批纯后端,与你有义的是:不新增任何依赖。

## 已核实接线事实(主会话 2026-10-02 验证,可直接引用)

1. **SSR 控制器位置**:
   - `jauth-hub-core/src/main/java/io/github/oatelauser/jauth/core/web/ConsentController.java` — GET /oauth2/consent,含 org 三态判定(OrgConsentView 私有 record)、scopeItems、grantedScopes(D4 已授权徽标)、**org 选择的会话暂存副作用**(`CeilingAwareOAuth2AuthorizationService.CONSENT_ORG_SESSION_KEY_PREFIX + state` 写 session)。
   - 同目录 `DeviceVerifyController.java` — GET /device/verify,只出 educational 一个标志。
   - `jauth-hub-selfservice/.../web/SudoController.java` — GET /selfservice/sudo,`safeReturnTo()` 为 package-private static(以 / 开头且非 //,否则回退 /selfservice/apps);sudoEnabled = SudoGate bean 在场 && passkey.enabled()。
2. **starter 协议链**:`jauth-hub-starter/.../JauthHubAutoConfiguration.java` 的 `jauthProtocolSecurityFilterChain`(约 692 行起)。securityMatcher = 框架协议端点 ∪ /login、/oauth2/consent、/device/verify、/me、core CSS(passkey 开时再加 webauthn 两路径)。授权规则:/login、CSS、/me permitAll;/oauth2/consent、/device/verify **authenticated**。非 text/html 的未认证请求 401(MediaTypeRequestMatcher 入口点)。新 /api 路径必须加进 securityMatcher 并逐路径显式声明规则(类注释"宿主链共存四规则",绝不写 anyRequest 兜底)。
3. **控制器注册方式**:core 无组件扫描,SSR 控制器由 starter 的 @Bean 方法注册(JauthHubAutoConfiguration 内,自行定位);selfservice 控制器在 `JauthSelfServiceAutoConfiguration.PageConfiguration` 注册(照 SudoController bean 形态,ObjectProvider 可缺省)。
4. **响应 SPI**:`io.github.oatelauser.jauth.core.response.ResponseRenderer`(starter 恒有默认实现 bean,注入即用);selfservice 控制器构造器注入 ResponseRenderer 是既定形态(PatController 先例)。
5. **CSRF**:链内无显式 csrf() 配置 → Spring Security 6+ 默认(XorCsrfTokenRequestAttributeHandler,CsrfFilter 惰性 token,请求属性可取,头名 X-CSRF-Token)。
6. **i18n**:MessageSource 由容器供给(messages*.properties 在 app 模块,core/selfservice 经注入使用,ConsentController 先例);JSON 返回服务端解析好的字符串,前端不做 scope 描述 i18n。
7. sudo 页不在协议链(部署方 default 链负责认证),其 JSON API 同理,**不动 starter 链**;selfservice 的 SudoInterceptor/SensitiveScopeSudoInterceptor 全局注册但按注解生效,不影响无注解的状态 GET。

## 任务清单

### 新增(core)

- `core/web/ConsentPageAssembler.java`(命名可微调,意图不变):把 ConsentController 的视图装配逻辑(org 三态 + scopeItems + grantedScopes + clientName 解析 + org 会话暂存副作用)提取为可复用类。**SSR 控制器改为委托它,行为逐字段零变化**——org 三态、已授权徽标、会话暂存副作用全在装配层。
- `core/web/ConsentStateController.java`:GET `/api/consent`(参数同 SSR:client_id/state/scope/org),produces JSON,委托装配器,ResponseRenderer 包装,另附 CSRF 字段。
- `core/web/DeviceVerifyStateController.java`:GET `/api/device/verify`,produces JSON。

### 新增(selfservice)

- `selfservice/web/SudoStateController.java`:GET `/api/sudo?returnTo=`,复用 `SudoController.safeReturnTo`(同包 static)与 SudoGate/PasskeyFlag 判定,ResponseRenderer 包装。
- `JauthSelfServiceAutoConfiguration`:注册该 bean(照 jauthSudoController 形态)。

### 修改(starter)

- `JauthHubAutoConfiguration`:认领 `/api/consent`、`/api/device/verify`(securityMatcher + 授权规则两条均 authenticated,镜像 SSR 同名页),注册两个 core 状态控制器 bean。

## JSON 契约(B3 前端将照此消费,字段名就是接口)

- GET /api/consent → data:{ clientId, state, clientName, educational, orgGuide, orgChoices:[{orgId,orgName,href}], orgBadge(可 null), scopes:[{name,description,checked,grantable,alreadyGranted}], csrfToken, csrfHeaderName }
  - orgChoices.href 照装配器现值(SSR 重入链接,前端可忽略);org 会话暂存副作用必须与 SSR 完全同路径发生。
- GET /api/device/verify → data:{ educational, csrfToken, csrfHeaderName }
- GET /api/sudo → data:{ educational, sudoEnabled, returnTo(已消毒), csrfToken, csrfHeaderName }
- CSRF 字段来源:CsrfFilter 惰性请求属性(CsrfToken),csrfToken 取 token 原始值,csrfHeaderName 取 token.getHeaderName()。

## 测试(必做)

1. **CSRF 契约探针(starter,最关键)**:真链 slice 测试——GET 状态接口取回 csrfToken 原始值后,(a) 以 `X-CSRF-Token` 头携带原始值 POST 受 CSRF 保护的端点 → 必须通过;(b) 以 `_csrf` 表单参数携带原始值 POST → 必须通过。**若默认 XorCsrfTokenRequestAttributeHandler 拒绝原始值,立即停:越界即停汇报,不得自行改全局 CSRF 配置**(主会话裁决)。
2. consent 状态 JSON:个人客户端 plain 态、org 三态(guide/select/selected)、selected 态断言 **session 暂存副作用**、alreadyGranted 徽标数据、未认证 401。
3. device 状态 JSON:educational + CSRF 字段 + 未认证 401。
4. sudo 状态 JSON:sudoEnabled 两态、returnTo 消毒(外站 URL 回退看板)。
5. SSR 回归:既有 ConsentController/SudoController/DeviceVerifyController 测试全数保持绿(行为零变化的自证)。

## 约束

- **禁做**:git 操作;版本号改动(全仓保持 1.3.0);新增依赖;改全局 CSRF 配置;动登录页/B2 范围;改 SSR 页面行为或模板。
- 代码风格:AGENTS.md(Clean Code/SLAP、Rationale 注释、阿里 p3c);javadoc 中文 + `@author oatelauser`;jspecify `@Nullable`。编辑期 hook 会自动格式化并做回合级评审——发现当场修。
- 测试风格照所在模块既有测试(standalone MockMvc 先例;限流/时序类测试不得依赖睡眠)。

## 验收标准(自测命令)

```
mvn -B verify
```
期望:BUILD SUCCESS(8/8 模块),三门禁(Spotless/p3c/SpotBugs)绿,测试数 ≥ 460 + 本批新增全绿。失败就地修复,不带病汇报。

## 汇报格式(≤20 行,只要结论与证据)

1. 结论一句话(完成/部分完成+原因)
2. 变更文件清单(路径,一行一个)
3. 新增测试数与测试总数
4. mvn verify 结果行(BUILD SUCCESS/Tests run 计数)
5. CSRF 契约探针结论(头/表单两路各自通过与否)
6. 临时文件清单(无则写"无")
