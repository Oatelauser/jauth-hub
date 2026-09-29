# SecurityFilterChain 基线、白名单与 401/403 渲染

## 红线：必须自配 FilterChain 且默认 denyAll

本模块不产生安全链，只挂注解。没有业务自配的 `SecurityFilterChain`，所有接口裸奔。基线范本（examples 的 `SecurityExampleConfig`）：

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/public/**").permitAll()   // 显式白名单
                        .requestMatchers("/secure/**").authenticated()
                        .anyRequest().denyAll())                     // 默认全拒绝（fail-closed）
                .httpBasic(Customizer.withDefaults());               // 认证方式由业务定（JWT/OAuth2/Basic）
        return http.build();
    }
}
```

## 白名单匹配器（security.utils.matcher）

- `AnonymousRequestMatcher`：收集 `@RequiresNonLogin` 注解端点的路径，自动汇入白名单（实现 `HandlerMethodProcessor`，启动期扫描 HandlerMethod）
- `AbstractAntRequestMatcher`：自定义 Ant 风格路径匹配器的基类

放行路径一律走白名单声明；不要在鉴权注解上留空值"绕过"（启动期就失败）。

## 401/403 渲染（SecurityExceptionAdvice，1.1.0+）

`SecurityExceptionAdvice`（位于 `security.autoconfigure` 包，`@RestControllerAdvice` + `@Order(100)`）是模块级异常 advice 的首个落地：SERVLET 应用且 web 错误引擎就位时自动装配，先于全局 `GlobalExceptionAdvice` 被咨询。

**denied 透传**：`AccessDeniedException` 家族（含 `AuthorizationDeniedException`）原样 rethrow——403 语义交还 Security 的 `ExceptionTranslationFilter` 翻译。

**认证异常翻译**：`AuthenticationException` 家族按子类型映射 web 错误码，经 `ExceptionOutputEngine` 统一渲染为 **401**（协议探测 / 日志策略 / 已提交补写全部保留）：

| 异常 | 错误码 | 含义 |
|---|---|---|
| `BadCredentialsException` | `A0210` | 用户密码错误 |
| `LockedException` | `A0202` | 用户账户被冻结 |
| `DisabledException` | `A0203` | 用户账户已作废 |
| `CredentialsExpiredException` | `A0212` | 用户密码已过期 |
| `AccountExpiredException` | `A0213` | 用户账户已过期 |
| `SessionAuthenticationException` | `A0230` | 用户登录已过期 |
| 其余（含 `InsufficientAuthenticationException`） | `A0301` | 访问未授权，请先登录 |

排障口径：

- **401 未认证**：请求没带凭证 / 会话过期 → 查认证链路（FilterChain、token 解析），与注解无关
- **403 已认证无权限**：主体权限集合里缺注解声明的角色/权限键 → 查 `GrantedAuthority` 内容或自定义 Authorizer 的数据源
- web 侧原 `SECURITY_DENIED_CLASSES` 按类名让路的妥协已删除，denied 路由由本 advice 类型安全接管
