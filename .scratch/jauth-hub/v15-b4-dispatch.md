# v1.5 B4 派单词:demo 教学区 Vue 化(三层教学全量复刻)

> 自包含任务书。你只做本文范围内的事;范围外一律停下汇报。**禁止任何 git 操作**(提交权在主会话)。

## 背景与目标

v1.5 修宪后教学形态收缩进 /demo 专区,**三层教学(流程图高亮当前步骤 / HTTP 请求响应日志 / 折叠"发生了什么"说明块)在 Vue 里全量保留**(SPEC §7 v1.5 口径)。本批 = demo 四页迁入 jauth-hub-front。设计语言:教学区**保留原视觉基因**(暗色渐变+白卡片可回归——demo 就是教学形态的家),与主站企业级皮刻意区分;零外链照守。

**必读参考**(先读再写,不许发明):
- `.scratch/jauth-hub/research/04-v15-page-api-survey.md` §3.3(demoConfig 字段/端点)
- SSR 模板:`jauth-hub-app/src/main/resources/templates/demo/{index,token,api-call,callback}.html`
- SSR 助手:`jauth-hub-app/src/main/resources/static/demo/demo.js`(jauthDemo:PKCE verifier/challenge、state、httplog 面板、sessionStorage 流转)——**语义逐行对齐重写为 `src/demo/` 模块**(Web Crypto:randomUUID/randomValues + SHA-256 challenge;verifier/state 存 sessionStorage)

## 任务清单

### 1. 后端小增量(仅此两处,越界即停)

- **demo 公开客户端种子 redirect 白名单追加 `{issuer}/front/demo/callback`**(保留旧 `/demo/callback` 条目——SSR demo 活到 B5)。种子位置自行定位(jauth-hub.clients 播种配置/文档化示例,AppConfiguration 或 application.yml)。
- `DemoController.demoConfig` 装配:`redirectUri` 改指 `{issuer}/front/demo/callback`(SPA 回调;旧 SSR 回调路径由白名单旧条目继续兼容)。JSON 状态面(B1c 已发)自动随装配同源。

### 2. 前端 demo 区(`src/demo/` + 四页)

- `src/demo/flow.js`:PKCE 与 state 件(verifier 生成/challenge 派生/存取);`src/demo/httplog.js`:请求/响应日志面板状态(方法/URL/状态/耗时/响应体折叠)。
- 四页(壳外独立路由,**教学区自有布局**:顶部流程图步骤条高亮当前步 + httplog 侧栏 + 说明折叠块):
  - `/demo`(index):说明 + "开始授权"按钮 → 拼 authorize URL(client_id/scope/PKCE/state)顶层导航;
  - `/demo/callback`:读 code+state,校验 state,POST tokenEndpoint(client_id + code_verifier,form-encoded)换令牌,令牌入 sessionStorage,httplog 全程记录;错误态(error 参数)如实展示;
  - `/demo/token`:令牌展示(access/refresh/有效期)+ **内省演示**(POST introspectEndpoint,机密客户端 rsClientId/rsClientSecret + access token,展示活跃/scope/username/orgs);
  - `/demo/api-call`:持 access token GET whoamiUri(SimpleResponse.ok 形状解包),展示用户视图。
- 配置:GET `/api/demo/config`(无 csrf,公开面);四页共用。
- 导航壳追加"教学区"单项(`/demo`)。

### 3. i18n

- demo 区 zh/en(普查:index 8/token 15/api-call 10/callback 9 + 流程图步骤/说明块键);教学文案照 SSR 键义迁移。

## 约束

- **禁做**:git 操作;除任务书第 1 节两处外的任何后端改动;新增 npm 依赖;动 B2b/B3 已发页面(ShellLayout 导航数组追加除外);package.json 1.4.0;运行时外链。
- rsClientSecret 边界照旧(dev 教学夹具,页面教学文案明示生产内省凭证只在资源服务器后端)。
- 自测:`npm run test` 全绿(含:challenge 派生向量测试、state 校验、httplog 记录、config 消费)+ `npm run build` + dist 外链扫描零命中;后端两处增量 `mvn -B verify` 全绿。

## 汇报格式(≤15 行)

1. 结论一句话;2. 变更文件清单(前端/后端分列);3. 测试数与 build/verify 结果;4. 三层教学复刻点各一行;5. 偏差与理由(如有);6. 临时文件清单(无则写"无")。
