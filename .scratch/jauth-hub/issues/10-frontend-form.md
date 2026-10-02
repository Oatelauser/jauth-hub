# 10 前端形态决议(SSR 皮 / headless / 分离前端)

Type: grilling
Status: resolved(2026-10-02)
Blocked by: —

## Question

用户 2026-10-01 提出(v1.3 待办池 #1):jauth-hub 前端形态三选——维持 SSR / headless 增量(JSON 认证 API + 自带 UI,SSR 保留默认)/ 前后端全面分离。先 grilling 后动工;须回答 issue 05 选 SSR 的三前提(教学定位 / 零外链 / 宿主嵌入白得 UI)哪条失效。

## Answer(grilling 2026-10-01~02)

**概念先澄清(三问三答后收敛)**:「内置」指**所有权**——页面由 jauth-hub 产品自己发布、部署在认证中心域名,与渲染技术(SSR/SPA)正交;「前后端分离」是工程形态,接入方与自家皆可分离。支付宝类比:密码页永远是认证中心自家的,第三方只能跳转不能代画,否则钓鱼乐园。GitHub = 前后端分离 + 登录页自家,两件事同时成立。

**决议:**

1. **界线宪法(进 SPEC §1)**:信任面四页(登录/consent/设备/sudo)永远是「jauth-hub 自家的页面」;内置 Thymeleaf 皮为默认、原样保留;自助面六页可换可缺(selfservice 本就是可选模块);接入方前端形态与 jauth-hub 无关(OAuth 即解耦契约)。
2. **headless 增量 = v1.4 首批**:四页**全部** JSON API 化(用户拍板 QB=b,非最小闭环),SSR 皮保留默认;混合用法成立——不自带 UI 的页落回内置皮。
3. **jauth-hub-front**:仓库根目录新模块,**Vue 3 + Vite 工程**(QC 初裁 a vanilla → 2026-10-02 终裁 b,用户「前后端分离的那种」);调用 JSON API 渲染信任页,部署于认证中心**同域名**,构建产物随制品发布;兼作生态用户「GitHub 式认证中心前后端分离」示范。不在 Maven reactor,CI 增 node 步骤(v1.4 落地时详设)。
4. **宪法两条(进 SPEC §1)**:
   - 教学三层(「发生了什么」说明块 / demo HTTP 日志 / 流程图高亮)**保留继续维护,但不再绑架架构**——架构改动与其冲突时教学让路;
   - **运行时零外链永守**:页面永不引第三方 CDN 资源(JS/CSS/字体);框架(如 jauth-hub-front 的 Vue)必须打进制品随发布,运行时仍零外链。
5. **注记**:sudo 页物理在 selfservice 模板但**属协议面**,不随自助面可选化;长期是否挪 core/web 随 v1.4 headless 实施一并考虑。
6. **角色三分(澄清记录)**:jauth-hub-app = 认证中心本体(独立部署,**零业务代码**);业务应用 = 各自独立工程引 rs-starter 护接口(SPEC §0 主场景「统一登录」);examples/embedded-demo = 单应用嵌入教学样板。jauth-hub-front(v1.4)= 认证中心自家分离前端,非业务代码宿主。

**成本底账(2026-10-01 探查实测)**:19 模板 / 2103 行 HTML / 15 GET 页面路由;内联 vanilla JS 约 800 行;无构建链;th:each ~15、th:if ~100、sec:authorize 0(页面逻辑浅);真渲染断言集中在 app 模块 6 个集成测试,core/selfservice 页面测试全 standalone;headless 局部先例 = PatController / AuthorizedAppsController 已有 produces=JSON 的 list 接口。

**issue 05 三前提清算**:教学定位 → 降级保留(决议 4a);零外链 → 升格宪法(决议 4b);宿主嵌入白得 UI → 仍成立(内置皮即白得),headless 为其补 API 面。**三前提无一失效,SSR 皮不动。**

## 翻转记录（2026-10-02，v1.4 发布当日用户拍板）

**v1.5 前端归一**，本票决议 1/2 的"SSR 永远默认/内置皮原样保留"作废：
1. resources 目录全部 SSR 模板页迁入 `jauth-hub-front`，内置 SSR 皮退场拆除；
2. front 双形态发行：同域名分离部署照常 + dist 打 jar 上 Maven Central（嵌入宿主加依赖白得 UI，"白得 UI"前提以新形态存续）；
3. 页面完整窜连（导航壳 + 流程链），非零散页面集合；自助面页面依赖 selfservice JSON API（front README 写明部署前提）；
4. 设计语言：主页面企业级认证中心形态，教学形态收缩 /demo 专区（票 05 教学定位同步修宪）。

Status: superseded-in-part（界线宪法第 1 条"内置皮默认保留"由本翻转记录取代；信任面自家所有权不变）
