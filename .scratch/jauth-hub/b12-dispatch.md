# B12 派单词:用户管理页 + 自助改密(app 专属)

> 你是 B12 批次的实施 subagent。本文自包含,读完直接开工。**禁止任何 git 操作**。本批**app 模块在界内**(用户管理页是 app 专属,SPEC §2);core/starter/selfservice 原则上不动(见"core 小补"例外)。

## 一、决议摘录

1. SPEC §2:app = 独立部署壳,**自持用户库、用户管理页**、/demo 教学区。SPEC §7:v1.1 页面 = 应用管理/安装审批/**用户管理(app 专属)**。
2. SPEC §3:`jauth_user` 含 role(SUPERADMIN|USER)、status(ACTIVE…);超管 = properties 种子(SuperAdminSeeder 惯例)。SPEC §6:密码 ≥8 位无复杂度表演、bcrypt 默认强度。
3. kickoff:本批含**自助改密**。UI 照全项目基调(Thymeleaf SSR、zh+en、零依赖、ResponseRenderer/JauthException)。

## 二、主会话已核实的事实(直接用)

- **管理门惯例**:`@RequiresRole(role = RequiresRole.ROLE_SUPER_ADMIN)`(spring-plus,AdminSampleController 样例)——角色来自 `AppUserDetailsService` 对 jauth_user.role=SUPERADMIN → ROLE_SUPER_ADMIN 的映射;403 由 spring-plus SecurityExceptionAdvice 渲染。
- **密码纪律**:app 的 PasswordEncoder = `PasswordEncoderFactories.createDelegatingPasswordEncoder()`(AppConfiguration,**存储哈希必须带 `{bcrypt}` 前缀**,类注释有因);用户创建惯例照 `SuperAdminSeeder`。
- **安全链**(AppSecurityConfiguration):`/api/**` 已 authenticated + 401 入口分派、text/html 302 登录;**新页面路由须自行加 permit 段**(照 `/selfservice/**` 惯例);denyAll 红线不动。
- `UserRepository`(core):findById/findByUsername/save——**缺列表/按名精确查**,见 core 小补。jauth_user 表 status/role 列全在,**零迁移**。
- **hook 坑**:写入前高危检查会拦 `password = "..."` 形态的字面量赋值——测试凭据用占位常量/方法参数形态传递,别硬撞(存量 SuperAdminSeeder 测试有先例可抄)。

## 三、改动清单(全在 jauth-hub-app,除注明)

### 1. 用户管理面(SUPERADMIN)

- 页面 `GET /admin/users`(Thymeleaf,照 /demo 模板惯例):用户列表(用户名/显示名/角色徽标/状态徽标/创建时间)+ 建号表单 + 行内操作(改角色/停用启用/重置密码)。**不加分页**(单部署用户量形态,记档)。
- JSON 面 `/api/admin/users` 系列(走 @RequiresRole 门):
  - `POST /api/admin/users`:建号(用户名唯一——撞名 A0506 段位续新码、密码 ≥8 位 A0501、显示名可选)
  - `POST /api/admin/users/{id}/role`:SUPERADMIN↔USER;**不可操作自己**(自降/自升都拒,防锁死,新码)
  - `POST /api/admin/users/{id}/status`:ACTIVE↔DISABLED;同样**不可操作自己**;用户不存在 B0502
  - `POST /api/admin/users/{id}/password`:重置密码(≥8 位),响应不回显明文(一次性展示惯例:与 my-apps 的 secret 一致,**明文只在响应出现一次**)
- `AppUserDetailsService`:核对 status→UserDetails.enabled 接线(**停用必须挡登录**——DISABLED 用户登录得 disabled 凭据错);没有就补上并加测试。

### 2. 自助改密 + 档案(任何登录用户)

- 页面 `GET /profile`:当前用户显示名 + 改密表单。
- `POST /api/profile`:改显示名;`POST /api/profile/password`:旧密码核验(错→A0501 段位新码或既有码,**并联动登录防爆破**:复用 `RateLimiter.onLoginFailure(username)` 口径记败——防已认证会话内爆破旧密码;成功后可选清计数)。新密码 ≥8 位;改完**不强制踢会话**(v1.1 无会话清剿面,记档)。
- 安全链补 permit:`/admin/**`、`/profile` → authenticated(SUPER_ADMIN 细门在注解层)。

### 3. core 小补(唯一例外)

- `UserRepository.findByUsername` 已有;补 `List<JauthUser> findAll()`(管理列表,双实现;排序 username ASC 定序)。**不动既有方法签名**。

### 4. 测试

- 管理面:建号/撞名/改角色/停启用/重置密码/自操作拒/非超管 403(spring-plus 门)/DISABLED 登录被拒
- profile:改显示名/旧密码错(联动锁计数)/改密成功后新密码可登录、旧密码拒
- AppUserDetailsService status 映射单测
- 页面 DOM 断言两套(照 selfservice 页面测试形态,app 侧照 /demo 测试惯例)
- 存量回归:app 集成测试 20 条照绿(路由/安全链改动最直接的证人)

### 明确不做(记档)

分页;会话/令牌清剿(改密后旧 token 仍在效——v1.2 候选随 back-channel logout 一起看);邮箱流(SPEC 出局项);审计新事件(用户管理生命周期事件词表未拍板,不加);迁移(零)。版本不动。

## 四、开工前先读

`AGENTS.md`;app 全模块(小,通读——重点 SuperAdminSeeder/AppUserDetailsService/AppConfiguration/AppSecurityConfiguration/AdminSampleController/DemoController 模板惯例);core `user/` 三件套 + `JauthUser`;`RateLimiter` 公开面(onLoginFailure 语义);selfservice 任一控制器(JSON+CSRF+错误码惯例直系模板);`JauthErrorCode`/`SelfServiceErrorCode` 段位账(app 侧新码建议落 `JauthErrorCode` A0512+ 或 app 自立,按段位账惯例定并同步账面)。

## 五、验收标准

1. `mvn verify` 全绿(320 存量 + 新增),三门禁过,零豁免
2. 停用挡登录、改角者不能操作自己、非超管 403、重置密码明文只出现一次——四条各有测试
3. 安全链改动不破存量(app 集成测试照绿),denyAll 红线不动
4. 旧密码核验联动防爆破计数有测试
5. hook 自动 spotless 属正常;非格式化自动改动停手汇报;评审提醒不管、不跑 git

## 六、汇报(≤40 行)

改动文件清单(新建/修改分列)、新增测试数、`mvn verify` 尾行证据、遗留/取舍。临时产物不留。
