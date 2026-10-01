# C4 施工单:@RequiresScope 三合一(core/starter/examples)——v1.2 批次 4/5

> 你是施工 subagent(或配额受限时由主会话代执)。项目根 `D:\workspace\CC\jauth-hub`(Windows)。
> 必读:本单全文 + AGENTS.md。前置:C1-C3 已合入(da2ef08)。
> **铁律:禁止任何 git 操作;只做本单范围;`mvn verify` 全绿;发现施工单事实与实际冲突 → 停下汇报。**

## 1. 任务一句话(v1.1 滑账 #6 晋升,用户已确认)

方法注解 `@RequiresScope` 三合一:**①自动注册 scope 词典**(同 JVM 扫描)+ **②rs-starter/spring-plus 权限键对齐**(声明式 scope 校验)+ **③desc 兜底文案**(注解 desc 流到 consent 展示)。边界(既定):注解扫描只覆盖同 JVM(嵌入宿主/app 自有接口);独立部署的外部资源服务器仍显式注册。

## 2. SPEC 摘录(冲突即停)

- §3:scope 目录 = 代码枚举 + i18n 描述,**不建表**;目录外 scope 被 A0505 拒——自动注册正是补"宿主自有接口的 scope 进目录"闭环
- §2:rs-starter 薄封装**零 spring-plus 依赖、零 core 依赖**(pom 实锤)——**本批不动 rs-starter**
- §1:无 CDN;§8 质量门禁照 AGENTS.md

## 3. 已核实接线事实(直接采信)

1. **ScopeCatalog(core/scope)**:`register(ScopeDefinition)`(同名 upsert 幂等)/`find`/`all`;starter 注册 InMemoryScopeCatalog bean(内置 openid/profile/email 三枚)
2. **ScopeDefinition**:record `(name, i18nKey, sensitive)`,唯一工厂 `of(name, sensitive)`(i18nKey = `jauth.scope.<name>` 前缀工厂,**禁手拼**);`sensitive` 语义注释"sudo 触发用"——本批注解 `sensitive` 属性只携带不消费
3. **consent 兜底现状**:ConsentController:156 `messageSource.getMessage(definition.i18nKey(), null, name, locale)`——缺 key 已兜到裸名;③要做的是**注解 desc 优先于裸名**
4. **rs-starter 映射**:JauthOpaqueTokenIntrospector 把 scope 按可配 `authority-prefix`(默认 `SCOPE_`)映射 authorities——②的对齐 = 校验同时认 `name` 原串(= spring-plus 权限键形态,冒号格式)与 `SCOPE_` 前缀两种;自定义前缀宿主仍走手写规则(文档化)
5. **C3 范式可复用**:RequiresSudo 注解(core.web)+ SudoInterceptor(HandlerInterceptor:非注解直通/HandlerMethod 判定)+ starter 的 WebMvcConfigurer 注册先例(jauthStaticResourcesConfigurer :520)——@RequiresScope 照此形态
6. **examples 现状**:embedded-demo OrdersController `/api/orders` 无 scope 校验(注释"业务侧可直接用 scope 判断");DemoSecurityConfiguration 用 JauthResourceServerConfigurer;宿主 seed 客户端走 `jauth-hub.clients[0].*` properties

## 4. 实施清单

**A. core.web**:
- `@RequiresScope` 注解:属性 `value`(scope 名,冒号格式 `orders:read` 为推荐形态=spring-plus 权限键对齐)、`desc`(兜底文案,默认空)、`sensitive`(默认 false);javadoc 写明三合一语义与同 JVM 边界
- `RequiresScopeInterceptor`(照 SudoInterceptor 形态):HandlerMethod 且带注解 → 取当前 `Authentication` authorities,含 `value` 原串**或** `SCOPE_`+value 即放行;否则抛 `AccessDeniedException`(**生态标准异常**——从 MVC 层抛出会穿透到宿主链的 ExceptionTranslationFilter 渲染 403;spring-plus SecurityExceptionAdvice 与 Boot 默认都正确处理;javadoc 说明宿主有 catch-all advice 时需 rethrow,jauth 自有 advice 不拦它);非注解方法零开销直通
**B. core.scope**:`ScopeDefinition` 加 `@Nullable String fallbackDesc` 字段(第 4 位);`of(name, sensitive)` 保持兼容委托 `of(name, sensitive, null)`;新工厂 `of(name, sensitive, @Nullable desc)`;全库 grep `new ScopeDefinition(` 直接构造点(预期为零,有则改走工厂)
**C. core.web.ConsentController**:156 行兜底序改为 `fallbackDesc 非空 → fallbackDesc,否则 name`(i18n key 命中仍最优先)
**D. starter(JauthHubAutoConfiguration)**:
- 扫描注册 bean:`SmartInitializingSingleton`(或 ApplicationRunner——照 seeder 先例选)遍历 `RequestMappingHandlerMapping` 全部 HandlerMethod,方法/类上有 `@RequiresScope` → `ScopeCatalog.register(ScopeDefinition.of(value, sensitive, desc))`,INFO 日志一行(注册了什么);幂等靠 catalog upsert;启动序在 seeder 之后无依赖冲突(catalog 是内存对象)
- `WebMvcConfigurer` 注册 RequiresScopeInterceptor(全局,照 jauthStaticResourcesConfigurer 形态)
**E. examples/embedded-demo**:OrdersController `/api/orders` 挂 `@RequiresScope(value = "orders:read", desc = "读取订单")`;demo seed 客户端 properties 的 scopes 加 `orders:read`;README(双语)补 @RequiresScope 三合一说明段(简短:注解用法 + 冒号键对齐 + 外部 RS 显式注册边界)
**F. 测试**:
- 注解扫描:起 context 含 @RequiresScope 桩控制器 → catalog.find 命中(name/sensitive/desc 全对);无注解 context 不注册
- 拦截器:authority 原串/SCOPE_ 前缀两态放行、缺 scope 抛 AccessDeniedException、非注解直通(照 SudoInterceptorTest 形态)
- ScopeDefinition 工厂兼容:of(name,sensitive) 落 fallbackDesc=null
- consent 兜底:definition 带 fallbackDesc 且 i18n 缺 key → 展示 desc;fallbackDesc 空 → 裸名(照既有 consent 测试基建,若 ConsentController 测试在 ProtocolPagesTest 则适配)
- examples:scope 命中 200 / 缺 403(照 embedded-demo 既有测试基建,若无法造缺 scope 令牌则以拦截器单测覆盖并在汇报注明)
- 既有 385 测试不动即绿(默认关?本批**无开关**——注解不挂即零行为,挂了才生效;说明:这与 passkey/sudo 不同,声明式校验不改变任何既有请求路径行为)

## 5. 明确不做(越界即停)

rs-starter 任何改动(零 core 红线);sensitive 的 sudo 联动消费(仅携带);scope 分层/通配(§5 梯级 scope 既有语义不动);新错误码;数据库表;@RequiresScope 参数级(方法级足矣)

## 6. 验收标准(主会话逐条对)

1. `mvn verify` 全模块绿;测试数较 385 只增不减
2. 无注解零行为变化(既有测试为证);三合一各有断言(注册/校验/兜底)
3. 汇报 **≤ 40 行**:文件清单 + 测试数变化 + verify 尾部证据 + 偏差与遗留
4. 临时产物不留;**不做 git 操作**
