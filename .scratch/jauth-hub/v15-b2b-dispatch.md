# v1.5 B2b 派单词:导航壳 + 自助面七页迁移(页面窜连主干)

> 自包含任务书。你只做本文范围内的事;范围外一律停下汇报。**禁止任何 git 操作**(提交权在主会话)。

## 背景与目标

B2a(dbe1b36)已落:企业级设计系统(app.css token + 组件类)、四页换皮、A0515 分支。本批 = **导航壳 + 自助面七页**,消费 B1 已发的状态端点与既有操作端点。设计语言/组件类照 B2a 现场代码,后端**零改动**。普查档案 `.scratch/jauth-hub/research/04-v15-page-api-survey.md` §2 有每页的端点清单(file:line)。

## 任务清单

### 1. 导航壳(ShellLayout)

- 新 `src/components/ShellLayout.vue`:左侧导航(品牌区 + 导航项)+ 顶栏(当前用户名 + 退出);布局路由挂 `/` 下,子路由渲染进内容区。
- 导航项(**只挂本批存在的页**):看板 `/selfservice/apps`、访问令牌 `/selfservice/pat`、我的应用 `/selfservice/my-apps`、我的组织 `/selfservice/my-orgs`、通行密钥 `/selfservice/passkey`(profile/admin 项归 B3,demo 项归 B4,不预挂)。
- 顶栏:用户名经 GET `/api/profile`(B1a 已发)取 `username`;退出 = 原生表单 POST `/logout`(带 `_csrf`,值取自任一状态面——shell 自持 GET `/api/selfservice/apps` 状态或 /api/profile 的 csrf 字段)。
- 认证守卫:子路由挂载前状态面 401 会经 api.js 分流整页跳登录,无需自建守卫(YAGNI)。

### 2. 路由(vue-router,base /front/ 已定)

`/login /consent /device-verify /sudo` 保持壳外独立路由(信任页不进导航壳);新增壳内:`/selfservice/apps`、`/selfservice/pat`、`/selfservice/my-apps`(注册做页内对话框/子视图,不独立路由——my-app-new 复用)、`/selfservice/my-orgs`、`/selfservice/orgs/:orgId/apps|installations|members`、`/selfservice/passkey`。

### 3. 七页(端点与行字段照普查 §2;操作端点全部已存在,字段照 SSR 模板内 JS)

- **看板 apps**:已授权应用表(clientId/clientName/scopes/lastAuthorizedAt)+ 行内 revoke(确认对话框);passkeyEnabled 时给通行密钥入口链接。
- **pat**:令牌表 + 创建表单(scope 复选 + 有效期阶梯 validity-{days} 前端字典)+ revoke;**创建成功 token 仅此一次展示**(明示不可再查)。
- **my-apps**:应用表 + 注册对话框(name/redirectUris)+ 编辑 + 轮转 secret(**shown-once**)+ 删除(确认);轮转/删除触发 sudo 时走 A0515 分支自动跳。
- **my-orgs**:组织表(orgId/orgName/role)+ 创建;OWNER 行链到三个 org 子页(窜连)。
- **org-apps / org-installations / org-members**:OWNER 面;A0508 失败渲染为页面错误态(无权限不白屏);installations 双分区(pending/全量)+ status pill(前端字典 `status-{枚举名}`)+ approve/reject/revoke;members 列表 + 按用户名添加 + 移除/角色切换(sudo 类)。
- **passkey**:凭据表(credentialIdShort/label/createdAt/lastUsedAt)+ 注册通行密钥(**框架 ceremony:options→register→完成刷新**,照 SSR passkey.html 内联 JS 流程,复用/扩展 src/webauthn.js)+ 删除凭据(确认 + DELETE `/webauthn/register/{credentialId}`,带 CSRF 头)。

### 4. returnTo 空间修正(B2a 遗留,重要)

- api.js A0515 分支**去掉 `/front` 前缀还原**——returnTo 直接用 `window.location.pathname + window.location.search`(SPA 路径 `/front/selfservice/...`)。服务端 safeReturnTo 只认本站路径,`/front/...` 天然合法;Sudo 验证成功后 `window.location.href = state.returnTo` 即**回到 SPA 页**,闭环不再落 SSR。
- 同步更新既有 spec(A0515 断言)。

### 5. 登录落点修正

- Login.vue:POST 成功且 `redirectUrl === '/'` → 导航 `/front/selfservice/apps`(SPA 看板);saved request(/oauth2/authorize...)照旧原样导航(协议流程必须服务端走)。

### 6. i18n

- 七页文案键入 zh/en(普查每页 key 数,约 190 静态 + 动态族 validity-{30|90|365}、status-{PENDING|APPROVED|REJECTED|REVOKED});scope 描述仍由服务端出,不进字典。

## 约束

- **禁做**:git 操作;后端任何改动;新增 npm 依赖;动 B2a 已发四页的契约与表单目标(仅 Login.vue 落点分支、api.js A0515 修正两处例外);package.json 版本 1.4.0;运行时外链。
- 代码风格:组件小而直、注释只写为什么;危险操作(删除/轮转/撤销)必须确认对话框;shown-once 凭据(token/secret)明示不可再查。
- 自测:jauth-hub-front/ 下 `npm run test` 全绿 + `npm run build` 无报错 + dist 外链扫描零命中。规格:每页至少一条渲染/动作冒烟(列渲染、注册表单载荷、status pill、A0515 修正、登录落点)。

## 汇报格式(≤18 行)

1. 结论一句话;2. 变更文件清单;3. 测试数与 build 结果;4. 窜连点清单(哪些页间链接/流程链落地,一行);5. 偏差与理由(如有);6. 临时文件清单(无则写"无")。
