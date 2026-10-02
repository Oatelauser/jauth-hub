# D0 派单:真实用户路径全上下文真渲染测试(v1.3)

## 背景(为什么做)

v1.2.1 真机排雷一天爆出 3 个 v1.0 潜伏 bug(selfservice 模板解析器缺 ApplicationContext、看板全 NULL 时间行 NPE、`/` 落点 403),全部是 standalone 测试盲区。本批把「全 context 真渲染」覆盖扩展到**全部自助页 + 登录全链**(根路径已有 RootRedirectIntegrationTest/HomePathPropertyIntegrationTest 覆盖,只允许补缺口,不许重复)。

## 唯一权威范式(照抄结构,已核实)

`jauth-hub-app/src/test/java/io/github/oatelauser/jauth/app/web/SelfServicePageRenderingIntegrationTest.java`:
- `@SpringBootTest(classes = JauthHubAppApplication.class, webEnvironment = MOCK)` + `@AutoConfigureMockMvc`
- `@TestPropertySource(properties = ...)`:**每类一个独立 H2 mem 库名**(如 `jauth-pat-render-it`,MODE=PostgreSQL,DB_CLOSE_DELAY=-1)、superadmin 播种(`jauth-hub.bootstrap.superadmin.*`)、需要时 `jauth-hub.passkey.enabled=true` / `jauth-hub.sudo.enabled=true`(测试属性开,合法;**repo 的 application.yml 永不动**)
- 认证:`with(user("superadmin").roles("SUPER_ADMIN"))`;locale 钉 `Locale.SIMPLIFIED_CHINESE`(CI en 环境教训)
- 断言:status 200 + `contentTypeCompatibleWith(TEXT_HTML)` + 无 "C0101" + 至少一个 zh 词条/页面特征

其他可参考:org 数据 fixtures 的建法看 selfservice 模块 `OrgAppInstallationFlowIntegrationTest`;落点行为看 `HomePathPropertyIntegrationTest`;登录/consent 表单 CSRF 姿态看 core 既有测试。

## 任务清单(全部在 app 模块 io.github.oatelauser.jauth.app.web,命名 *IntegrationTest)

1. **PAT 页** `/selfservice/pat`:空列表态渲染 + 至少一条数据态(建 PAT 的方式照 PatController 既有测试)。
2. **Passkey 页** `/selfservice/passkey`(需 passkey.enabled=true):空列表渲染,断言页面词条。
3. **我的应用两页** `/selfservice/my-apps`、`/selfservice/my-apps/new`:空态 + 建一个应用后的列表态。
4. **组织三页** `/selfservice/my-orgs`、`/selfservice/orgs/{orgId}/apps`、`/selfservice/orgs/{orgId}/installations`:fixtures 建 org+成员+至少一个 org 应用/安装;含一条负路径(访问不存在的 orgId → 404 或 403,按控制器实际语义断言)。
5. **sudo 页** `/selfservice/sudo` GET 渲染(sudo.enabled=true;returnTo 参数按 SudoController 实际签名)。
6. **登录全链** `LoginFlowIntegrationTest`(独立 H2 库):GET /login 真渲染含表单;passkey 开/关两态的按钮有无各断言一次;POST 正确凭据(带 csrf)→ 302(落点语义按 HomePathPropertyIntegrationTest);POST 错误密码 → 200 重渲染错误态(断言稳定词条);未认证 GET /selfservice/apps → 302 /login。

每页最低断言:200 + text/html + 无 C0101 + zh 词条。数据为空是合法态,必须渲染不炸。

## 禁做(越界即停,写进汇报)

- **改任何 src/main 生产代码**——发现潜伏 bug 也只记录不修(主会话裁决)
- 任何 git 操作(提交权在主会话)
- 改 repo application.yml 开关;引新依赖;改既有测试断言语义(可读不可动)

## 验收标准

- 仓库根 `mvn verify` 全绿(8 模块 + examples);测试数只增不减(当前 401)
- 新类遵循项目注释规范(javadoc 说明"为什么"+ @author oatelauser + @DisplayName 中文)

## 汇报格式(≤20 行,只要结论与证据)

1. 新增文件清单 + 每类测试方法数
2. mvn verify 证据(surefire 总数 / 各模块结果一行)
3. 发现的潜伏 bug 列表(如有;不修)
4. 临时文件清单(有则删,target/ 除外)

(本单自包含;若执行中断,主会话照单代执。)
