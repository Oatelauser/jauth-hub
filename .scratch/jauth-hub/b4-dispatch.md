# v1.4 B4 派单词:同域名装配 + CI node 步骤 + 文档同步

> 自包含任务书。你只做本文范围内的事;范围外一律停下汇报。**禁止任何 git 操作**(提交权在主会话)。

## 背景与目标

B1/B2 已落地信任面四页 JSON API(/api/login GET+POST、/api/consent、/api/device/verify、/api/sudo),B3 已落地根目录 `jauth-hub-front/`(Vue 3 + Vite,路由 base /front/,四路由 /login /consent /device-verify /sudo,构建产物 dist/)。本批 = 让分离前端**部署于认证中心同域名**成为开箱能力:皮肤旗标路由 + 静态装配 + CI 加 node 步骤 + 文档同步。SSR 皮永远默认,零行为变化是本批的回归底线。

## 已核实接线事实

1. **皮肤旗标先例**:core.web 的 `EducationalFlag`/`PasskeyFlag` 接口形态(core 只给接口与默认常量,starter 属性装配落地);selfservice 经 ObjectProvider 持有、宿主缺它降级(PasskeyFlag OFF 先例)。
2. **四个 SSR 页控制器**:core.web LoginController(/login)、ConsentController(/oauth2/consent)、DeviceVerifyController(/device/verify,均 starter 注册 bean);selfservice SudoController(/selfservice/sudo,selfservice 自动配置注册)。
3. **框架重定向链路**:LoginUrlAuthenticationEntryPoint("/login") 302 前会保存 saved request;B2 的 POST /api/login 成功回 redirectUrl=saved request 优先——所以皮肤路由用 **302 重定向**(保 URL 语义与 SPA 路由 base 一致),不用 forward。
4. **app 安全链**:app 自持 default 链(RootController 落点路由 /),管理链 denyAll;/front/** 静态资源是登录前皮肤,须在 app default 链 permitAll(照 CSS/静态资源先例,自行定位 AppConfiguration/安全配置)。
5. **CI**:`.github/workflows/ci.yml` 单 verify job(setup-java 21 + mvn -B verify)。
6. Boot 默认静态资源:classpath:/static/** 自动挂 /** 映射;dist 若落 app 的 classpath:/static/front/ 则天然可服务;但 history 路由深链(/front/login 无物理文件)需 fallback index.html。

## 任务清单

### 1. 皮肤旗标(core + starter + selfservice)

- core.web 新增 `TrustSkinFlag` 接口(照 EducationalFlag 形态):`boolean frontEnabled()`,默认常量 SSR=()→false。javadoc 写明宪法:SSR 永远默认,front 只是备选皮部署形态(SPEC §1、issues/10)。
- starter `JauthHubProperties` 增 `trustSkin`(枚举 ssr|front,默认 ssr,属性 `jauth-hub.trust-skin`);`JauthHubAutoConfiguration` 注册 TrustSkinFlag bean 供四页控制器注入。
- 四个 SSR 控制器 GET 入口加同一守卫分支(下沉私有方法,SLAP):frontEnabled → **302 redirect:/front/<路由>?<原样保留全部查询串>**(login→/front/login、consent→/front/consent、device→/front/device-verify、sudo→/front/sudo;查询串经 request.getQueryString() 原样转发,空串安全)。默认 ssr 分支行为零变化。
- selfservice SudoController 注入:ObjectProvider<TrustSkinFlag> 降级 SSR(照 PasskeyFlag 先例),自动配置构造点同步。

### 2. 静态装配(app + pom)

- app 安全配置:/front/** permitAll(default 链)。
- app WebMvcConfigurer:/front/** 资源处理器 + **PathResourceResolver fallback 到 /front/index.html**(history 深链;有扩展名 miss 照常 404,防把 /front/logo.png 也回 index)。
- 根 pom 或 app pom 增 Maven profile `front-skin`(默认关):maven-resources-plugin 在 generate-resources 阶段把 `${maven.multiModuleProjectDirectory}/jauth-hub-front/dist` 拷进 app classpath:/static/front——dist 缺失时 profile 显式失败并提示先构建前端(fail-fast,不静默出无皮制品)。
- 本地开发:README 一句话给 spring.web.resources.static-locations=file:../jauth-hub-front/dist 的免打包联调法(不写代码,纯文档)。

### 3. CI(ci.yml)

- 增独立 `front` job(与 verify 并行):checkout + setup-node 22(cache npm)+ `npm ci`(在 jauth-hub-front/ 下)+ `npm run test` + `npm run build`;verify job 不依赖它(普通 Java CI 不被 node 阻塞)。front job 里 dist 是否上传 artifact 不做(YAGNI,发布流程 B5 另议)。

### 4. 文档(双语 README + SPEC)

- README.md / README.en.md:特性表加 v1.4 headless 行(JSON API 端点表 + jauth-hub-front 构建部署一节 + trust-skin 旗标说明);测试总数暂不冻结(B5 收口再改),版本徽章不动。
- docs/SPEC.md:§1 前端行补"v1.4 已落地"口径;§2 模块结构图加 jauth-hub-front 一行;§5 v1.4 行标进行中(完成翻转归 B5)。
- docs/RELEASE_PROCESS.md:阶段 3/4 各加一句——发布若携带 front 皮肤,先 npm build 再 `mvn -Pfront-skin` 打包(产物随制品)。

### 测试(必做)

1. 默认 ssr:既有页面测试全绿(零行为变化自证)。
2. skin=front(app 集成测试,@TestPropertySource jauth-hub.trust-skin=front):四页 GET 各 302 到 /front/<路由> 且**查询串原样保留**(login ?error、consent client_id/state/scope/org、sudo returnTo)。
3. 静态装配:src/test/resources 放 static/front/index.html 夹具 → GET /front/index.html 200;深链 GET /front/login 200 回 index 内容;GET /front/logo.png(不存在)404 不回 index。
4. profile 逻辑不自动跑(默认关);CI 绿在 push 后由主会话核对。

## 约束

- **禁做**:git 操作;版本号改动;动 jauth-hub-front/ 内容(B3 产物只读);动 B1/B2 的 API 面与 SSR 默认行为;新增 Java/前端依赖(maven-resources-plugin 版本由父 pom/BOM 管则不算新)。
- 代码风格照 AGENTS.md 与既有形态(javadoc 中文、@author oatelauser、Rationale 注释、p3c)。
- 自测:mvn -B verify 全绿。汇报 ≤20 行:结论、文件清单、测试数、verify 结果行、临时文件清单。
