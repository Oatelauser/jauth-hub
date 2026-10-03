# v1.5 B5b 派单词:SSR 全拆除 + 嵌入契约重构(v1.5 破坏性收尾批)

> 自包含任务书。你只做本文范围内的事;范围外一律停下汇报。**禁止任何 git 操作**(提交权在主会话)。
> 前置:B5a 已落 front-dist 模块与接线(现场以 git log 最新 B5a 提交为准)。

## 背景与目标

v1.5 前端归一的拆除半场:全部 SSR 模板/Thymeleaf/视图解析设施退场,页面路由改指 SPA,信任面四页 GET 变无条件 302。**动作以"删"为主,测试网重写是主工作量**。上下文档案:.scratch/jauth-hub/v1.5-kickoff.md(暂停快照 B5b 要点)、research/04(普查)。

## 任务清单(六节)

### 1. 模板与静态资源删除

- core templates(login/consent/device-verify + fragments/layout)、selfservice templates 全部(含 sudo/pat/apps/my-apps/my-app-new/my-orgs/org-apps/org-installations/org-members/passkey)、app templates(profile、admin/users、demo/ 四页)、app static/demo/demo.js、core static/css/jauth.css。
- **动手前先 Glob 全量枚举** templates 与 static 目录,汇报附完整删除清单(不许凭记忆)。

### 2. Thymeleaf 与视图设施退场

- 依赖:core/starter/selfservice/app 四 pom 的 thymeleaf 相关依赖删除(spring-boot-starter-thymeleaf / thymeleaf-spring6 等,以各 pom 实际为准)。
- starter:模板引擎/视图解析器 bean、模板解析前缀配置、CSS_PATTERN 链认领(securityMatcher + permitAll 行)删除;**i18n 复合 MessageSource 保留**(scope 描述仍服务端解析,ConsentPageAssembler 消费)。
- selfservice:视图解析器双链与 viewNames 白名单配置删除。
- `messages*.properties` **原样保留**(scope 键在内;页面 chrome 键变死键——不修剪,v1.6 滑账)。

### 3. 页面路由改指 SPA(无条件 302)

- LoginController/ConsentController/DeviceVerifyController:GET 改无条件 `"redirect:/front/<路由>?" + 原查询串`(get passthrough 逻辑保留,frontTarget 助手挪进控制器或保留在 TrustSkinFlag 处);**TrustSkinFlag 接口、jauth-hub.trust-skin 属性、JauthHubProperties.TrustSkin 枚举全部删除**;SSR 视图名常量与装配参数清理。
- SudoController:GET 改无条件 302 /front/sudo;safeReturnTo 静态保留(SudoStateController 消费)。
- selfservice/app 各 SSR 页控制器的 page GET 方法与视图名删除(**JSON 操作端点与 State 控制器一字不动**);DemoController 页 GET 删除(demoConfig static 与 DemoConfigStateController 保留)。
- RootController(home-path):默认值改 `/front/selfservice/apps`(属性机制不变)。

### 4. 滑账回收 + 契约整理

- **createdAt**:AdminUsersController.summary(或状态行装配)补 `createdAt`,AdminUsersState 行带上——B3 滑账回收,SPA AdminUsers.vue 加该列(一行模板)。
- app pom 的 front-skin profile 删除(已被 B5a dist 模块依赖取代);AppWebConfiguration /front/** 出网保留(dist jar 在 classpath 即服务)。
- EducationalFlag 与 educational 字段**保留不动**(契约稳定;退场 v1.6 滑账)。
- README(双语):快速入门去除 trust-skin 旗标叙述,改"front 即默认唯一皮;嵌入宿主引 jauth-hub-front-dist 白得 UI"。

### 5. 测试网重写(最大工作量)

- D0 真渲染网(app 15 GET 路由)与 core/selfservice 页面测试:SSR 渲染断言全部改为 **302 + Location 指向 /front/<路由> + 查询串保真**断言。
- FrontSkinIntegrationTest:旗标用例改无条件;静态装配断言保留(夹具仍在)。
- JauthMemoryModeIntegrationTest:CSS 认领断言删除。
- embedded-demo 测试:只断言重定向,**不得依赖 dist 内容**(默认构建 dist 模块是空 jar)。
- 涉及 Thymeleaf 的测试基建(standalone 视图渲染先例)同步清理;MockMvc standalone 无视图渲染的既有教训适用。

### 6. 收尾

- 全仓 grep:`thymeleaf|Thymeleaf|VIEW_|viewNames|trust-skin|TrustSkin|front-skin` 残留清零(文档中归档性提法除外——SPEC/kickoff 历史叙述保留)。
- SPEC §7 的"`jauth-hub.educational` 开关随 SSR 拆除一并退场"口径与实际(保留挂账)不符——SPEC 该句改为"educational 字段随状态 API 契约保留,退场挂 v1.6 滑账"(一行修正)。

## 验收标准(自测)

```
mvn -B verify          # BUILD SUCCESS 8/8(+front-dist 9 模块)
mvn -B verify -Pdist   # 同绿(enforcer 活)
```
门禁绿;测试数净变化如实汇报(重写为主,新增 createdAt 相关 +1~2)。

## 约束

- **禁做**:git 操作;版本号改动(1.4.0);动 JSON API 面(状态/操作端点零变化);动 jauth-hub-front/(createdAt 列一行例外);动框架端点与链的协议端点认领;删 messages*.properties。
- 就地修复不重派;发现拆除波及未列文件(如某测试 import 模板类)属正常级联,修完在汇报列明。

## 汇报格式(≤20 行)

1. 结论一句话;2. 删除文件总数(模板/静态/配置分列)+ 新增/重写测试数;3. 两态 verify 结果;4. 残留 grep 清零证明(一行);5. 级联修复清单;6. 临时文件清单(无则写"无")。
