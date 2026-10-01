# C2 施工单:Passkey 页面与流程(selfservice/core/app)——v1.2 批次 2/5

> 你是施工 subagent。项目根 `D:\workspace\CC\jauth-hub`(Windows)。
> 必读:本单全文 + AGENTS.md。前置批次 C1 已合入(commit 5e61db8,后端地基全绿)。
> **铁律:禁止任何 git 操作;只做本单范围;`mvn verify` 全绿;发现施工单事实与实际冲突 → 停下汇报。**

## 1. 任务一句话

给 C1 的 passkey API 地基补上**用户可见的页面与前端流程**:登录页"使用通行密钥登录"按钮(条件渲染)、selfservice 通行密钥管理页(注册/列表/删除)、登录事件桥扩展(passkey 登录进审计与限流语义)、i18n、README 用户面。**零 CDN 外链、零新依赖。**

## 2. SPEC 摘录(冲突即停)

- §1:Thymeleaf SSR + 手写单文件 CSS,**无框架、无 CDN 外链**;UI 中文 + messages_en 骨架
- §7:页面带折叠"发生了什么"教学块(EducationalFlag 模式);视觉沿袭暗色渐变+白卡片
- §5:Passkey 开关默认关——**库默认关;app 参考部署同样默认关**(配置样例注释给出)
- §3:审计只记生命周期;§6 登录防爆破走限流器(限流语义见本单 §3.6)
- B12 实战教训(v1.1 付费买来):**app/selfservice 页面测试断言中文文案必须钉 locale,且确认消息源不受系统 locale 回退污染**

## 3. 已核实接线事实(直接采信)

1. **框架端点面(C1 已挂链)**:POST `/webauthn/register/options`(需认证)、POST `/webauthn/register`(需认证,body `{publicKey:{credential:{...}, label}}`,响应 `{success:true}`)、POST `/webauthn/authenticate/options`(permitAll,响应含 base64url challenge/rpId/userVerification)、POST `/login/webauthn`(permitAll,成功 `{redirectUrl, authenticated}`);**DELETE `/webauthn/register/{credentialId}`** 为框架自带删除端点(ownership 由框架 `CredentialRecordOwnerAuthorizationManager` 把关,非本人凭据 403)。C1 已 `disableDefaultRegistrationPage(true)`——页面全归你
2. **凭据列表无框架端点**:自建(见 §4.B);数据源 `UserCredentialRepository.findByUserId(Bytes)`(user handle = jauth_user.id 的 UTF-8 Bytes,`JauthUserEntityRepository.userHandle(userId)` 静态方法做编解码)
3. **登录页**:`core/web/templates/login.html`——formLogin 的 CSRF 走 hidden input `th:name="${_csrf.parameterName}" th:value="${_csrf.token}"`(JS 从 DOM 取);布局 fragments/layout(head/steps/httplog);渲染它的 controller 在 core web(自行定位,LOGIN_PATH)。**按钮与 JS 条件渲染**:照 `EducationalFlag` 先例做一个 passkey-可见性注入(仅 `jauth-hub.passkey.enabled=true` 时模板渲染按钮+脚本块)
4. **JS 形态**:**内联 `<script>` 写进模板**,不建静态资源目录(避免动协议链 securityMatcher;SPEC 零外链)。需要 ~15 行 base64url↔ArrayBuffer 帮助函数。登录流:取 CSRF → POST options → `navigator.credentials.get({publicKey})` → POST `/login/webauthn` → 成功按 `redirectUrl` 跳转;**失败(含 404 UserNotFoundError——凭据已删但旧 options 的 allowCredentials 失效)→ 提示后重走 options**。注册流同理 POST options → `navigator.credentials.create`(带 label 输入框)→ POST register
5. **selfservice 范式**:`PatController` + `pat.html` 是最近亲缘(用户凭据自助管理页)——控制器形态/模板结构/i18n key 命名/错误处理照它;导航入口照 PAT 在看板页(apps.html)的链接样式加"通行密钥"
6. **登录事件桥(本批唯一 core 改点)**:`core/audit/SecurityEventAuditBridge` 现在 `isFormLogin` 只认 `UsernamePasswordAuthenticationToken`(C1 已核实:WebAuthn 过滤器走标准事件线,事件被此过滤器丢弃)。扩展:passkey 认证(`WebAuthnAuthenticationRequestToken`/`WebAuthnAuthentication` 两形状)也进 **LOGIN_SUCCESS/LOGIN_FAILED 审计**(detail 加 `factor=webauthn`);**限流器只在该事件携带可解析用户名时登记**——Rationale:密码锁定防的是在线口令猜测,passkey 断言验签不是猜测预言机,usernameless 断言无从按用户名键控,不造假桶
7. **i18n**:core `core/i18n/messages_zh.properties`+`messages_en.properties`(登录页词条);selfservice `selfservice/i18n/` 同构(管理页词条)
8. **AppErrorCode 尾号 A0514**(app 模块);selfservice 错误处理照 PatController 的既有约定,不为 passkey 强造新码——确有用户面错误才加(如需,app 用 A0515+,selfservice 照其自身约定)

## 4. 实施清单

**A. 登录页(core)**:login.html 加"使用通行密钥登录"按钮(passkey 开启才渲染)+ 内联 JS 完整流程 + 教学块词条(jauth.login.passkey.*);登录失败提示复用 alert 样式;按钮放在表单下方分隔区
**B. 管理页(selfservice)**:`PasskeyController`(`/passkey` 页面渲染 + 凭据列表;列表项:label、创建时间、最近使用、credentialId 缩略;删除走框架 DELETE 端点,JS 带 CSRF)+ `passkey.html` 模板(注册表单:label 输入+按钮;凭据卡片列表;删除带确认;教学块"什么是通行密钥/私钥不出设备");看板导航入口;i18n 双语。注意:该页路由在 app 宿主默认链(selfservice 无自有链),照 PAT 页模式
**C. 登录事件桥(core)**:§3.6 扩展 + 单测(成功/失败事件 → 审计词与 detail;无用户名断言不进限流)
**D. app 集成**:`application.yml` 加注释掉的 `jauth-hub.passkey.*` 配置样例(默认关;rp-id/allowed-origins 缺省从 issuer 推导的说明写注释);README + README_en 功能清单加 Passkey 条目与配置说明(简洁,别写操作手册)
**E. 测试**:①登录页:关→无按钮无脚本;开→按钮/脚本在、locale 钉住断言中文词条(B12 教训)②管理页:渲染含列表/注册表单/导航、删除路径(JS 无法端到端,断言页面元素与端点接线即可;C1 已有端点级覆盖)③桥:见 C ④既有 366 测试不动即绿

## 5. 明确不做(越界即停)

sudo/strong_auth_at(C3);@RequiresScope(C4);改动协议链 securityMatcher/授权规则(C1 已定形);新静态资源目录与 /js 路由(内联脚本);新 Maven 依赖;框架 DELETE 端点的重新实现

## 6. 验收标准(主会话逐条对)

1. `mvn verify` 全模块绿;测试数较 366 只增不减
2. 默认关:登录页零可见变化、无新增渲染开销断言
3. 内联 JS 无外链(grep 模板无 http(s):// 资源引用)
4. 桥扩展有单测;限流登记的条件语义有断言
5. 汇报 **≤ 40 行**:文件清单(新增/修改分列+一行职责)+ 测试数变化 + verify 尾部证据 + 偏差与遗留(若有)
6. 临时产物不留;**不做 git 操作**
