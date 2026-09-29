---
name: spring-plus-security-starter
description: spring-plus-framework 的声明式鉴权能力使用约定（坐标 io.github.oatelauser:spring-plus-security-starter）。覆盖：@RequiresRole/@RequiresAdminRole 角色控制、@RequiresPermission(source,action) 权限键自动拼接、@Authorize/@PostAuthorize 指定 Authorizer、@RequiresNonLogin 匿名放行、@Principal 登录主体注入、Authorizer/AnnotationAuthorizer 权限数据源 SPI、超管 SUPER_ADMIN 短路、AnonymousRequestMatcher 白名单匹配器、SecurityFilterChain 必须 denyAll 自配的红线、SecurityExceptionAdvice 401/403 错误码渲染。给接口加角色/权限校验、替代 @PreAuthorize SpEL 写法、对接权限数据源、排查 401 403 无权限问题时使用。统一响应/异常在 spring-plus-web-starter。
---

# spring-plus-security-starter

这个 skill 直接给 AI 使用。本文件是导航与全局规则；API 细节与示例在 `references/`。

**模块边界**：本模块只做**授权**（注解声明"需要什么角色/权限"+ 校验桥接）；**认证**（登录、token 解析、会话）由业务项目的 Security Filter 链提供。

## 按需加载参考文档

| 任务涉及 | 加载 |
|---|---|
| 注解用法：角色/权限/通用授权/主体注入/匿名放行、权限键拼接、AND 语义 | [references/annotations.md](references/annotations.md) |
| 对接权限数据源：实现 Authorizer SPI、@Authorize 指定、超管短路机制 | [references/authorizer.md](references/authorizer.md) |
| SecurityFilterChain 基线、白名单匹配器、401/403 渲染（SecurityExceptionAdvice） | [references/filter-chain-and-advice.md](references/filter-chain-and-advice.md) |

## 与原生 Spring Security 的关系（使用规则总纲）

本模块**不替换** Spring Security，只替换"授权声明与安全错误渲染"的表达方式。三个接管域：

1. **授权注解声明**：`@Requires*` 族替代 `@PreAuthorize` SpEL——拦截器由模块自动装配（`AuthorizationManagerBeforeMethodInterceptor` / `AfterMethodInterceptor`），**无须业务侧 `@EnableMethodSecurity`**
2. **认证/授权异常渲染**：401/403 错误体由 `SecurityExceptionAdvice` 接管（认证异常映射 A02xx/A0301，denied 透传给 Security 翻译 403）
3. **白名单路径收集**：`@RequiresNonLogin` 端点由 `AnonymousRequestMatcher` 自动收集

原生惯用法 → 本模块写法对照：

| 原生 Spring Security 惯用法 | 本模块写法 |
|---|---|
| `@PreAuthorize("hasRole('ADMIN')")` | `@RequiresRole(role = "ADMIN")`；超管端点 `@RequiresAdminRole` |
| `@PreAuthorize("@ss.hasPermission('user','delete')")` | `@RequiresPermission(source = "user", action = "delete")`（权限键 `user:delete`） |
| `@PostAuthorize("...")` | `@PostAuthorize(beanClass = XxxAuthorizer.class)` 指定自定义授权器 |
| `SecurityContextHolder.getContext().getAuthentication()` 手取主体 | `@Principal` 注入 Controller 方法参数 |
| 手写 `AuthenticationEntryPoint` / `AccessDeniedHandler` 渲染错误 JSON | 已由 `SecurityExceptionAdvice` 接管，不要重复配（要改文案走 web 异常映射体系） |
| 大片 `permitAll()` 白名单 | 显式白名单 + `@RequiresNonLogin` 收集；**基线方向不变：默认 denyAll** |
| 权限数据在库/远程服务 | 实现 `Authorizer` / `AnnotationAuthorizer` 注册为 Bean（默认 `GrantedAuthorityAuthorizer` 读 authorities） |

以下照常自配，本模块**不做认证**：

- `SecurityFilterChain` / `HttpSecurity`、`UserDetailsService`、`PasswordEncoder`
- 登录 / token 解析 / 会话 / JWT / OAuth2 等认证链路全套
- CSRF / CORS / 会话管理等其余 Security 能力
- 若因其他原生注解开启了 `@EnableMethodSecurity`：与模块拦截器共存，但**同一方法不要两套注解重复声明**

## 安全红线（最高优先级，先于一切规则）

1. **必须自配 `SecurityFilterChain` 且默认 `denyAll`，放行走显式白名单**——本模块不替你关大门；没配 FilterChain 的应用所有接口裸奔（贴几个注解 ≠ 安全）
2. `@RequiresRole(role = {})` 与 `@RequiresPermission` 空 source/action 是配置错误：**启动期直接失败**（fail-closed），不要捕获或绕过
3. 授权语义 fail-closed：注解存在但解析为空权限 → 拒绝（不是放行）
4. 基线参照：examples 的 `SecurityExampleConfig`（denyAll + 白名单 + 演示账号）

## 核心决策规则

1. 角色控制 → `@RequiresRole(role = "USER")`；超管端点 → `@RequiresAdminRole`（元注解，归并为 `ROLE_SUPER_ADMIN`）
2. 权限控制 → `@RequiresPermission(source = "user", action = "delete")`，权限键自动拼接 `user:delete`
3. 登录主体 → `@Principal` 注入方法参数，不从 `SecurityContextHolder` 手动取
4. 权限数据源对接 → 实现自己的 `Authorizer`（或用默认的 `GrantedAuthorityAuthorizer`），不改框架授权器
5. 放行路径 → `AnonymousRequestMatcher` 声明白名单，不在注解上留空绕过

## 不要这样做

- 不混用本模块注解与 `@PreAuthorize` SpEL（两套体系，排查困难；存量 SpEL 迁移时整体替换）
- 不把权限校验逻辑写进 Controller 方法体
- 不在 `@RequiresPermission` 里留空 `source`/`action`
- 已认证但无权限是 403、未认证是 401——排查时先分清这两类，别急着改注解（见 filter-chain-and-advice.md 渲染表）

## 文档同步约定

本 skill 的 API 断言以模块源码为唯一基准；模块行为变更时，模块 README 与本 skill（SKILL.md 及 references/）必须同步修改——只改一边视为未完成。
