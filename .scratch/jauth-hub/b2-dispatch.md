# v1.4 B2 派单词:登录页 JSON API(状态 GET + 认证 POST 桥)

> 自包含任务书。你只做本文范围内的事;范围外一律停下汇报。**禁止任何 git 操作**(add/commit/push 都不行,提交权在主会话)。

## 背景与目标

jauth-hub v1.4:信任面四页 JSON API 化。B1(已落地)交付了 consent/device/sudo 三页状态 GET(/api/consent、/api/device/verify、/api/sudo)与 CSRF 契约探针。本批 = 登录页:GET /api/login 页面状态 + **POST /api/login JSON 认证桥**——四页中唯一被框架 formLogin 独占的 POST,须复刻 UsernamePasswordAuthenticationFilter 的成功路径语义。

## SPEC 摘录(仅相关)

- 统一响应(SPEC §4):ResponseRenderer SPI,成功 00000,错误 A05xx 家族。
- 登录防爆破(SPEC §6):连错 5 次锁 15min,经 RateLimiter 实现;锁定期内不触达口令校验。
- 审计(SPEC §3):jauth_audit_event 只记生命周期事件,登录成功/失败在册。

## 已核实接线事实(主会话验证;B1 之后若现场与此不符,以现场为准并汇报差异)

1. **formLogin**:starter `JauthHubAutoConfiguration.jauthProtocolSecurityFilterChain` 内 `http.formLogin(form -> form.loginPage("/login").permitAll())`,无 defaultSuccessUrl(守卫注释:落点统一由 / 上的部署方路由接管)。POST /login 由框架过滤器消费,本批**不动它**。
2. **LoginLockoutFilter**(core/web):`addFilterBefore(..., UsernamePasswordAuthenticationFilter.class)`,构造时钉死 `loginPath` 且 `shouldNotFilter` 只认 `POST && path.equals(loginPath)`,用户名从**表单参数**读——形态上不覆盖 /api/login。结论:JSON 桥在控制器内做同语义锁门(见下),**不改此过滤器**。
3. **审计与计数是事件驱动**:`core/audit/SecurityEventAuditBridge` @EventListener 收 AuthenticationSuccessEvent / AbstractAuthenticationFailureEvent,**只认 UsernamePasswordAuthenticationToken 形状**(OAuth2 客户端认证被过滤)。JSON 桥的 authenticate() 走 UsernamePasswordAuthenticationToken,理论上自动进桥。
4. **AuthenticationManager**:上下文当前**无**暴露的 AuthenticationManager bean;formLogin 链自建。全局管理器可经 `org.springframework.security.config.AuthenticationConfiguration#getAuthenticationManager()` 取得(反映容器 UserDetailsService/PasswordEncoder bean——即嵌入契约里宿主提供的那个)。
5. **错误码族**:core 的 `io.github.oatelauser.jauth.core.response.JauthErrorCode`(A0501/0502/0506/0507/0508/0516/0517/0518 已占;0519 在 selfservice)。**A0520 起空闲**——动手前 grep 全仓 `A05\d\d` 复核防撞。
6. **CSRF 契约**(B1 探针已验证):默认 XorCsrfTokenRequestAttributeHandler 接受"GET 状态接口返回的原始 token + X-CSRF-Token 头"。GET /api/login 照 B1 形态带 csrfToken/csrfHeaderName 字段。
7. **会话事实**(C1 验证先例):changeSessionId 保留 session 属性(CSRF token 存活)。表单登录不打 sudo 强认证点(密码 ≠ 强认证,SecurityEventAuditBridge 注释),JSON 桥同样**不碰** strong_auth_at。
8. SSR 全链测试先例在 app 模块(D0 真渲染网,15 GET 路由 + 登录全链),有可借的完整授权码流程测试基建。

## 任务清单

### 新增(core)

1. `core/web/LoginApiController.java`(命名可微调,意图不变):
   - **GET /api/login** → data:{ educational, passkeyEnabled, error(请求带 ?error 参数即为 true,与 SSR 页同源语义), csrfToken, csrfHeaderName }。
   - **POST /api/login**:JSON body {username, password}。成功路径按序:
     a. 锁门:`rateLimiter.isLoginLocked(username)` → 401 + A0521(登录失败次数过多,稍后再试)——**锁定期内不触达口令校验**,与 LoginLockoutFilter 同 fail-closed 语义(在 javadoc 里写清为何不复用过滤器:表单形态钉死)。
     b. `authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(...))`;AuthenticationException → 401 + A0520(用户名或密码错误)——**不区分"用户不存在/密码错/已停用"**,防用户名枚举,与表单登录同形。
     c. 成功:复刻 AbstractAuthenticationProcessingFilter 语义——SessionAuthenticationStrategy(ChangeSessionIdAuthenticationStrategy,会话固定防护)→ SecurityContextHolder 置上下文 → SecurityContextRepository 持久化(DelegatingSecurityContextRepository = RequestAttribute + HttpSession,同过滤器默认)→ 200 data:{ redirectUrl }。redirectUrl = RequestCache 里的 saved request URL(登录入口点 302 到登录页前保存的 /oauth2/authorize?...),无则 `/`(与 SavedRequestAwareAuthenticationSuccessHandler 默认同形;home-path 路由交给 / 上的部署方落点,与 SSR 一致)。
   - 控制器薄,认证编舞(锁门+authenticate+会话动作)下沉到意图命名的协作者类,可脱离 MVC 单测。
   - **事件验证义务**:authenticate() 走全局 AuthenticationManager 时 AuthenticationSuccessEvent/失败事件是否真的发出(ProviderManager 是否 publish)——用测试证明;**若不发,由桥自己在成功/失败处 publishEvent(AuthenticationSuccessEvent/对应失败事件)**,javadoc 写明这是对表单过滤器行为的人工对齐(审计/限流桥只认事件源)。
2. JauthErrorCode 增补 A0520/A0521(中文 message,照族内风格;A0521 语义对齐 LoginLockoutFilter 非浏览器拒绝形态 login_locked)。

### 修改(starter)

3. `JauthHubAutoConfiguration`:认领 /api/login(GET+POST)并 permitAll(登录前页面与认证提交,镜像 /login);注册 LoginApiController bean;注册 `@ConditionalOnMissingBean AuthenticationManager`(经 AuthenticationConfiguration 取得)供注入。

### 测试(必做)

1. GET 状态:四字段齐、?error 反射 true、未认证可访问(permitAll)。
2. POST 成功:200、renderer 形状、redirectUrl = saved request 优先/无则 "/"、**session id 变化**(会话固定防护)、CSRF token 在新 session 存活、后续请求以该 session 已认证。
3. POST 失败:错密码 401 A0520、审计 LOGIN_FAILED 落库/计数 +1(事件源证明)、多次失败后锁门生效(A0521 且 AuthenticationManager 零调用——锁门先于认证的直接证据)。
4. POST 禁用用户:401 A0520(与错密码同形,防枚举)。
5. CSRF:无 X-CSRF-Token 头的 POST 被拒(403);带 B1 契约原始 token 通过。
6. **app 模块全链 E2E**:GET /api/login 取 token → POST /api/login(JSON+头)→ 持 session 走 /oauth2/authorize → consent → code → token 交换 → userinfo 200;**id_token 的 auth_time == JSON 登录时刻**(SAS 对认证时刻的取数口径若与预期不符,以解析 jar 的 javap 为准查明再落码——v1.3 教训,不凭记忆写框架 API)。
7. 回归:既有 460+B1 新增全绿,formLogin 表单路径零变化。

## 约束

- **禁做**:git 操作;版本号改动(保持 1.3.0);新增依赖;改 LoginLockoutFilter/formLogin 配置/全局 CSRF;动 B1 交付物(除非编译必需的最小接线);改 SSR 模板行为。
- 代码风格:AGENTS.md(Clean Code/SLAP、Rationale 注释、p3c);javadoc 中文 + `@author oatelauser`;jspecify `@Nullable`。编辑期 hook 自动格式化+回合级评审,发现当场修。
- 不引入新限流维度:锁门只按用户名键控,与既有 LoginLockoutFilter 同一 RateLimiter bean、同一键。

## 验收标准(自测命令)

```
mvn -B verify
```
期望:BUILD SUCCESS(8/8 模块),三门禁绿,测试全绿(总数 = B1 落地后总数 + 本批新增)。失败就地修复,不带病汇报。

## 汇报格式(≤20 行,只要结论与证据)

1. 结论一句话
2. 变更文件清单
3. 新增测试数与测试总数
4. mvn verify 结果行
5. 事件源结论(AuthenticationManager 自动 publish 或桥内人工对齐,二选一说明证据)
6. id_token auth_time 验证结论
7. 临时文件清单(无则写"无")
