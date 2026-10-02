# v1.4 B3 派单词:jauth-hub-front(Vue 3 + Vite 信任面四页分离前端)

> 自包含任务书。你只做本文范围内的事;范围外一律停下汇报。**禁止任何 git 操作**(add/commit/push 都不行,提交权在主会话)。

## 背景与目标

jauth-hub v1.4:仓库根目录新建 `jauth-hub-front/` 工程——Vue 3 + Vite 分离前端,调用 B1/B2 落地的 JSON API 渲染信任面四页(登录/consent/设备验证/sudo),兼作"GitHub 式认证中心前后端分离"生态示范(SPEC §1 前端行、issues/10 决议 3)。**不在 Maven reactor**(B4 才做装配与 CI)。SSR 皮是默认皮,你做的是备选皮,不改任何 Java/Maven 代码。

## 已核实的 API 契约(B1 已落地/B2 落地中,以后端实际为准)

统一响应:ResponseRenderer 形状 `{code, message, data}`,成功 code=`00000`;未认证 JSON 请求 401。

| 端点 | 方法 | 载荷(data 内字段) |
|---|---|---|
| /api/login | GET | educational, passkeyEnabled, error(?error 参数反射), csrfToken, csrfHeaderName |
| /api/login | POST | 请求 JSON `{username,password}` + 头 `X-CSRF-Token: <csrfToken>`;成功 `data:{redirectUrl}`(saved request 优先,无则 "/");失败 401 code=A0520(凭据错)/A0521(锁定) |
| /api/consent | GET | clientId, state, clientName, educational, orgGuide, orgChoices:[{orgId,orgName,href}], orgBadge(可 null), scopes:[{name,description,checked,grantable,alreadyGranted}], csrfToken, csrfHeaderName;参数 client_id/state/scope/org 与 SSR 同名 |
| /api/device/verify | GET | educational, csrfToken, csrfHeaderName |
| /api/sudo | GET | educational, sudoEnabled, returnTo(已消毒), csrfToken, csrfHeaderName;参数 returnTo |

**B2 落地的两条现场事实(必遵守)**:
1. **登录成功后 CSRF token 换发**(框架 CsrfAuthenticationStrategy 语义):GET /api/login 拿到的 token 在 POST /api/login 成功后即作废。登录页只需:POST 成功 → `window.location = data.redirectUrl`,该页不再发 POST;若某流程登录后还要 POST(如 consent 表单),必须先重新 GET 该页状态面取新 token——consent/device/sudo 各页自己的状态 GET 永远给当刻有效 token,按页取用即可。
2. redirectUrl 无 saved request 时为 `/`(浏览器 GET 302 到登录页才有 saved request;非浏览器 401 分支不存)。

**表单提交契约(SSR 同款,不许发明)**:consent 批准/拒绝、设备码提交是**原生表单 POST 浏览器导航**(fetch 不会带着授权码 302 到 RP 回调):
- consent → `POST /oauth2/authorize`,字段照 SSR 模板 `login/consent.html` 的表单(client_id/state/scope 勾选集 + `_csrf=csrfToken`);
- 设备码 → `POST /device/verify`,字段照 `device-verify.html`;
- sudo/passkey → `navigator.credentials.get()` 断言后 `POST /login/webauthn`,流程与字段**逐行照** `sudo.html` 的内联 JS;
- 登录页 passkey 按钮(当 passkeyEnabled)→ 同 `/login/webauthn` 流程,照 `login.html` 内联 JS。

## 必读参考文件(实现前通读,视觉与文案从这里来)

- 模板:`jauth-hub-core/src/main/resources/io/github/oatelauser/jauth/core/web/templates/`下 login.html / consent.html / device-verify.html / fragments/layout.html;`jauth-hub-selfservice/src/main/resources/io/github/oatelauser/jauth/selfservice/web/templates/sudo.html`
- 皮肤 CSS(设计语言:暗色渐变+白卡片,手写单文件):`jauth-hub-core/src/main/resources/io/github/oatelauser/jauth/core/web/static/css/jauth.css`
- 文案:`jauth-hub-app/src/main/resources/messages.properties` 与 `messages_en.properties`(zh 为准,en 骨架)

## 工程形态(锁死)

- 目录 `jauth-hub-front/`:package.json、vite.config.js、index.html、src/(main.js、router.js、api.js、i18n.js、assets/app.css、pages/Login.vue、pages/Consent.vue、pages/DeviceVerify.vue、pages/Sudo.vue)
- **Vue 3(`<script setup>` 组合式)+ vue-router 4**,路由 base `/front/`,四路由 /login /consent /device-verify /sudo;consent 页从 location.search 读 client_id/state/scope/org
- **依赖红线**:运行时仅 `vue` + `vue-router` 两个;dev 仅 vite、@vitejs/plugin-vue、vitest、@vue/test-utils、jsdom。无状态库、无 UI 框架、无 vue-i18n(自写 zh/en 字典,navigator.language 判 en,zh 默认——字典文案从 messages*.properties 拷贝对齐)
- **运行时零外链(SPEC §1 宪法)**:无 CDN、无 web 字体(系统字体栈)、无第三方图片;Vue 随制品打包
- fetch 包装:同源、解 ResponseRenderer 形状(code!=00000 或 !ok 抛错给页面渲染 message)、401 未认证跳 /front/login
- 教学层:educational=true 时各页渲染"发生了什么"折叠块(照 SSR 模板文案)
- 版本:node engines >=20,npm;首次生成 package-lock.json(提交),node_modules/ 与 dist/ 进 .gitignore(jauth-hub-front/.gitignore 自持,不动根 .gitignore)

## 测试(vitest,冒烟级即可但必须真断言)

1. fetch 包装:00000 解 data、非 00000 抛 message、401 跳登录。
2. Consent 页:scope 列表渲染(grantable=false 禁用、alreadyGranted 徽标、checked 预勾)、orgGuide/orgChoices/orgBadge 三态渲染。
3. Login 页:状态 error=true 渲染错误文案;提交构造 JSON body + CSRF 头(mock fetch)。
4. Sudo 页:sudoEnabled=false 渲染未启用态;returnTo 展示。

## 验收标准(自测命令,在 jauth-hub-front/ 下)

```
npm install
npm run test    # vitest run,全绿
npm run build   # 产 dist/,无报错
```

Maven 侧不许有任何改动;若发现后端契约与本文不符(以实际代码为准),停手汇报差异,不得前端擅自兜底改契约。

## 约束

- **禁做**:git 操作;Java/Maven/CI 文件改动(B4 范围);超出依赖红线的任何 npm 包;运行时外链资源;后端文件改动。
- 代码风格:组件小而直(单页单组件,不抽过早抽象);注释只写"为什么";文案 zh 默认 en 回退。
- 汇报 ≤20 行:结论、文件树(一层)、测试与 build 结果行、与 SSR 模板的差异说明(如有)、临时文件清单(无则写"无")。
