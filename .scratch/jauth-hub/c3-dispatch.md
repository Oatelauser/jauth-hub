# C3 施工单:sudo mode(core/starter/selfservice/app)——v1.2 批次 3/5

> 你是施工 subagent。项目根 `D:\workspace\CC\jauth-hub`(Windows)。
> 必读:本单全文 + AGENTS.md。前置:C1(5e61db8 后端地基)、C2(1b4a311 页面/桥)已合入。
> **铁律:禁止任何 git 操作;只做本单范围;`mvn verify` 全绿;发现施工单事实与实际冲突 → 停下汇报。**

## 1. 任务一句话

敏感操作前的 passkey 强验证(sudo):`jauth-hub.sudo.*` 开关(默认关,TTL 15min 可配)、passkey 认证成功打点 `strong_auth_at`、`@RequiresSudo` 注解 + 拦截器门、sudo 验证页、审计。**SPEC §5:sudo 依赖 Passkey——sudo.enabled=true 而 passkey.enabled=false 时启动 fail-fast。**

## 2. SPEC 摘录(冲突即停)

- §5:v1.2 强化层:sudo mode 开关,**依赖 Passkey**;§3:`jauth_user.strong_auth_at`(sudo 位)列已在,`UserRepository.updateStrongAuthAt(id, Instant)` v1.0 已备
- §1:无 CDN 外链、Thymeleaf SSR;§3 审计只记生命周期
- B12 教训:页面测试断言中文必须钉 locale

## 3. 已核实接线事实(直接采信)

1. **敏感端点(全 JSON API,精确清单)**:`POST /api/profile/password`(ProfileController:110,自助改密)、`POST /api/admin/users/{id}/password`(:190,管理员重置)、`POST /api/admin/users/{id}/role`(:139,角色变更)。**secret 轮转不存在**(B10 滑账未建)——本批不 gate 不预留。注意 `/api/profile`(显示名)与 `/api/admin/users`(建号)**不**属敏感面,不 gate
2. **打点点**:passkey 认证成功唯一服务器侧汇聚点 = `core/audit/SecurityEventAuditBridge.onLoginSuccess` 的 `isPasskeyLogin` 分支(C2 已加,经宿主 UserDetailsService 拿到 username 与 user)——此处追加 `userRepository.updateStrongAuthAt(user.id(), clock.instant())`。表单登录**不**打点(密码≠强认证)。桥构造器需加 `Clock`(starter 已有共享 Clock bean 先例,照引;既有 SecurityEventAuditBridgeTest 构造同步适配)
3. **gate 机制(决议)**:`@RequiresSudo` 注解(core web,PasskeyFlag 同域)+ `SudoInterceptor implements HandlerInterceptor`(selfservice):preHandle 检 handler 是否 `HandlerMethod` 且方法带注解 → `SudoGate.isFresh(username)` 不新鲜即 `throw new JauthException(A0515)`——**统一 403 JSON,不做浏览器 302 分叉**(敏感端点全是页面 fetch 调的 JSON API;fetch 跟随 302 会把 HTML 当 JSON 解析,分叉是错的)。页面侧:profile 页与 admin 用户页的既有 fetch 错误处理加 **A0515 分支 → `location = '/selfservice/sudo?returnTo=' + encodeURIComponent(location.pathname)`**(两页模板在 app 模块)
4. **SudoGate(core,user 域邻接)**:`isFresh(String username)`——findByUsername → `strongAuthAt != null && strongAuthAt.plus(ttl).isAfter(now)`;依赖 UserRepository + Duration ttl + Clock;纯逻辑单测友好
5. **sudo 验证页**:`SudoController`(selfservice,`GET /selfservice/sudo?returnTo=...`)+ 模板:passkey 按钮与 JS 照 C2 登录页内联脚本形态(options→credentials.get→POST /login/webauthn),成功后 JS 跳 `returnTo`(仅本站路径:以 `/` 开头且非 `//`,否则回退看板);**returnTo 服务端渲染前校验**;SudoGate 或 passkey 关闭时渲染"未启用"提示态(照 PasskeyController 门控形态)。已认证用户中途断言 = 就地升权(C1 已验:changeSessionId 不换 session 对象,CSRF token 存活)
6. **装配**:`JauthHubProperties` 加嵌套 `Sudo`(enabled=false、ttlMinutes=15,防御性拷贝范式);starter:sudo.enabled && !passkey.enabled → 启动 fail-fast(清晰报错);sudo 开时注册 `SudoGate` bean;selfservice autoconfig:注册 `WebMvcConfigurer` 加 SudoInterceptor(**仅 SudoGate bean 存在时**——关=零拦截零开销;starter 有 jauthStaticResourcesConfigurer :520 的 WebMvcConfigurer 先例)
7. **错误码**:全局序已用 A0501-A0514 → sudo 用 **A0515「需要强验证(sudo)」**,落 `SelfServiceErrorCode`(gate 在 selfservice;全局序纪律见 JauthErrorCode 类注释"按批增补不预铺")
8. **审计**:`AuditEventType` 加 `SUDO_REQUIRED("sudo.required")`,拦截器阻断时发布(detail 记目标路径;actor 反查照桥形态);通过(passkey 打点)已有 LOGIN_SUCCESS factor=webauthn 可见,不重复记
9. UX 语义(GitHub 同款,教学块写明):sudo 过期 → 操作被拦 → 验证页 passkey → 回原表单页 → **用户重填重交**(POST 不重放,简化是决议的一部分)

## 4. 实施清单

**A. core**:`@RequiresSudo` 注解;`SudoGate`(user 包);桥打点 + Clock;`AuditEventType.SUDO_REQUIRED`
**B. starter**:PasskeyFlag 旁加 sudo 属性 + fail-fast 校验 + SudoGate bean(Clock/UserRepository 注入)
**C. selfservice**:`SudoInterceptor` + WebMvcConfigurer 条件注册;`SudoController` + `sudo.html`(i18n 双语、教学块、CSRF meta、验证 JS、returnTo 校验与回退);viewNames 白名单第 9 视图;`SelfServiceErrorCode.A0515`
**D. app**:profile 页 + admin 用户页模板 JS 加 A0515 分支(跳 sudo 页);application.yml 注释样例(sudo 段,注明依赖 passkey);README/README_en 补 sudo 条目
**E. 测试**:①桥:passkey 成功打点(固定 Clock)/表单成功不打点 ②SudoGate:null/新鲜/边界 TTL ③拦截器:关=直通、开+新鲜=放行、开+过期=抛 A0515、非注解方法直通 ④sudo 页:开渲染(钉 locale)/关提示态/returnTo 外站拒绝回退 ⑤端到门:开启+过期时 POST /api/profile/password 得 A0515、打点后放行(MockMvc 造 strong_auth_at 状态)⑥默认关:既有 376 测试不动即绿

## 5. 明确不做(越界即停)

secret 轮转 gate(端点不存在);@RequiresScope(C4);协议链 securityMatcher 改动;/status 端点 gate(用户决议面外——汇报里提请用户拍板);POST 重放;新依赖

## 6. 验收标准(主会话逐条对)

1. `mvn verify` 全模块绿;测试数较 376 只增不减
2. 默认关零影响有既有测试为证;sudo 开而 passkey 关 → 启动失败有测试
3. 打点只发生在 passkey 成功路径(单测断言)
4. 汇报 **≤ 40 行**:文件清单(分列+一行职责)+ 测试数变化 + verify 尾部证据 + 偏差与遗留
5. 临时产物不留;**不做 git 操作**
