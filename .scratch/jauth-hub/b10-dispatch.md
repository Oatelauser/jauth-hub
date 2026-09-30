# B10 派单词:应用管理页 + 滑账①TransactionTemplate 收口 + 滑账②PAT 名列 V7(selfservice + core + starter)

> 你是 B10 批次的实施 subagent。本文自包含,读完直接开工。**禁止任何 git 操作**;越界(动 app 模块页面、改 pom 门禁、调测试阈值)即停并汇报。

## 一、决议摘录

1. SPEC §2:selfservice = 域数据自助页(看板/PAT/**应用管理**),app 依赖、宿主可选依赖;响应走 ResponseRenderer/`JauthException` 体系;错误码 selfservice 已占 A0503-A0505,增补从 A0509 起(段位账在 `JauthErrorCode` javadoc)。
2. SPEC §3:**个人应用免安装审批**(owner_user_id 直属);PAT 表"名称列缺失,B10 以 V7 加列迁移补,创建面补名称输入"(2026-09-30 拍板)。
3. 滑账①(owner 两列非原子 UPDATE):`JauthJdbcRegisteredClientRepository.save` = super.save + owner UPDATE 两条非原子语句,类 javadoc 明言"调用方自行决定是否包事务";B4 starter 已留 `jauthSeedingTransactionTemplate` bean(`@ConditionalOnMissingBean(TransactionTemplate.class)`,宿主自带则让位)——B10 应用管理写路径落位时收口。B8 的 `OrgService.create`(org+member 两写)javadoc 同样指向 B10。
4. UI 基调:Thymeleaf SSR + 手写单文件 CSS、中文 + messages_en 骨架、零依赖;协议端点禁包装。

## 二、主会话已核实的接线事实(直接用)

- selfservice 现有:`pat/`(PatService 接口:`create(String userId, Set<String> scopes, Duration validity)` + InMemory/Jdbc 双实现 + PatRecord/PatTokens/PatStatus)、`web/`(AuthorizedAppsController=已授权应用看板、PatController)、模板 `apps.html`(已授权页占用此名!)/`pat.html`、`SelfServiceErrorCode`、自家 `JauthSelfServiceAutoConfiguration`。
- B9 产物可直接用:`ClientOwnerResolver`(memory 实现带 `put` 登记表)、`InMemoryClientOwnerResolver`;client 构造惯例在 `ClientSeeder`/`ClientSeedProperties`(SPEC §6 TTL/PKCE 精确 redirect)。
- 事务模板:starter L1001 `jauthSeedingTransactionTemplate`;seeder 的既定用法 = "jdbc 模式若容器内有 TransactionTemplate 则整轮入事务"(L528 注释)。spring-tx 经 spring-jdbc 传递在 classpath,`TransactionOperations` 接口可在 core 引。
- memory 模式语义:`PatService` 已双实现照常;memory 下 client 存框架 InMemoryRegisteredClientRepository + owner 走 `InMemoryClientOwnerResolver.put`。

## 三、改动清单

### 1. 应用管理页(个人应用自助注册,最小面)

- 新建 `selfservice/web/MyAppsController`(`GET /self/apps` 列表 + `GET /self/apps/new` 注册表单 + `POST /self/apps` 注册)——**模板名用 `my-apps.html`/`my-app-new.html`,勿与已授权页 `apps.html` 撞名**;导航入口挂进已有自助页导航(pat/apps 页面若有导航区,照现有惯例)。
- 注册表单最小三件:**应用名**、**redirect URIs**(多行,精确 URL,沿用框架精确匹配语义)、**类型(公开/机密)**;scopes 不进表单——allowed scopes = scope 目录全集(封顶由 consent/ceiling 管,个人应用无 ceiling)。
- 新建 `selfservice/web/OwnedAppService`(或按模块惯例命名):`list(userId)` / `register(userId, name, redirectUris, confidential)`:
  - 构造 RegisteredClient 照 `ClientSeeder` 惯例(UUIDv7 id、对外 client_id 随机、PKCE 强制、SPEC §6 TTL、机密则随机 secret 框架编码、公开无 secret 无 refresh 框架自带)
  - **机密应用 secret 明文只在注册响应出现一次**(对齐 PAT 展示惯例);公开应用无此项
  - 双存储:jdbc = `JauthJdbcRegisteredClientRepository.save(client, ClientOwner.ofUser(userId))` **包 TransactionTemplate**(见 2);memory = 框架 repo save + `InMemoryClientOwnerResolver.put`
  - `list` 按当前登录用户列**个人应用**(owner_user_id = userId);机密应用的 secret 不回显
- **不做**(记档汇报):删除/编辑/secret 轮转(级联清授权/consent/安装/token family 未定,随 B12/B13);org 应用注册(B11"我的组织"上下文一起);审计事件不加(词表只记令牌/登录/consent/org 生命周期,应用注册非拍板项)。

### 2. 滑账① TransactionTemplate 收口

- `OwnedAppService` jdbc 写路径:client+owner 两语句包进 `TransactionTemplate`(取法照 seeder:`ObjectProvider<TransactionTemplate>` 可缺省;容器无事务管理器时退化为两语句并 javadoc 注明)。
- `core/org/OrgService.create`:org+member 两写包可选事务——构造器加 `@Nullable TransactionOperations`(spring-tx 接口,TransactionTemplate 即其实现),有则包裹、无(null)直通;javadoc 收掉"留 B10"欠条。starter jdbc 段注入既有 TransactionTemplate bean,memory 段传 null。rollback 测试:member 写失败时 org 不落库。

### 3. 滑账② PAT 名列(V7)

- `V7__pat_name_column.sql`:`ALTER TABLE jauth_pat ADD COLUMN name VARCHAR(100) DEFAULT NULL`(存量行 NULL,展示回退 i18n"未命名");双兼容单条 ALTER。
- `PatRecord` 加 `name` 字段;`PatService.create` 签名加 `String name`(非空校验);Jdbc/InMemory 实现同步列;`PatController` 创建表单加名称输入(必填)、列表展示名称;列表页空名回退;i18n zh+en。
- `FlywayMigrationTest`/`PostgreSqlMigrationTest`:V7 列断言 + 迁移数 6→7(若既有断言计数,连同 starter 的 `MAX(version)` 类断言一并联动——B8 已有先例)。

### 4. 测试

- OwnedAppService 双实现契约测试(注册→列表可见、机密 secret 一次性、公开无 secret);MyAppsController 页面测试(照 PatController/AuthorizedApps 测试模式,MockMvc DOM 断言);注册表单校验(名空/redirect 非法拒)
- OrgService 事务回滚测试(jdbc 侧,mock member 写失败→org 不落)
- PAT:名称必填、V7 后列表带名、存量 NULL 回退展示
- 迁移测试两库断言

## 四、开工前先读

`AGENTS.md`;selfservice 全模块(结构小,通读);`ClientSeeder`/`ClientSeedProperties`(client 构造与 secret 惯例);`JauthJdbcRegisteredClientRepository`(owner 两列手术面);`JauthHubAutoConfiguration` L520-560(seeder 事务用法)与 L1001(模板 bean);core/org B8 包;`PatController`+`pat.html`(表单/展示惯例);迁移测试两件。

## 五、验收标准

1. `mvn verify` 全绿(265 存量 + 新增),三门禁过,零豁免
2. 个人应用注册→列表闭环可用;机密 secret 只出现一次;POST 面走 JauthException/错误码体系
3. 滑账①收口:client+owner、org+member 两处两写均事务化(jdbc),且 starter 复用/让位宿主事务模板的既定语义不破
4. V7 双库过;PAT 创建面带名称;存量行不炸(回退展示)
5. 回合末 hook 自动 spotless 属正常;发现非格式化自动改动立即停手汇报;评审提醒不用管、不跑 git

## 六、汇报(≤40 行)

改动文件清单(新建/修改分列)、新增测试数、`mvn verify` 尾行证据、遗留/取舍(删除/轮转/org 应用注册等待记档项)。临时产物不留。
