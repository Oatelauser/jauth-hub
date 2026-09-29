# 鉴权注解族

包：`io.github.oatelauser.springplus.security.annotation`。

## 角色：@RequiresRole / @RequiresAdminRole

```java
@RequiresRole(role = "USER")          // 要求 ROLE_USER（ROLE_ 前缀由框架补）
@GetMapping("/profile")
public SimpleResponse<String> profile() { ... }

@RequiresAdminRole                    // 元注解：归并为 ROLE_SUPER_ADMIN，且走超管短路
@DeleteMapping("/user/{id}")
public SimpleResponse<Void> delete(@PathVariable Long id) { ... }
```

- `role()` 是 `String[]`，多角色语义为 AND
- 常量：`RequiresRole.ROLE_SUPER_ADMIN` / `ROLE_ADMIN` / `ROLE_USER`；超管名 `ADMIN_ROLE_NAME = "超级管理员"`

## 权限：@RequiresPermission

```java
@RequiresPermission(source = "user", action = "delete")   // 权限键 "user:delete"
@DeleteMapping("/user/{id}")
public SimpleResponse<Void> delete(@PathVariable Long id) { ... }
```

- `source` / `action` 均不可为空（启动期断言 fail-closed）
- `source` 以 `:*` 结尾表示通配前缀（如 `"tenant:*"` 命中 `tenant:get`、`tenant:delete`）
- `action` 惯例取 `ActionType` 常量：`list` / `get` / `create` / `update` / `delete` / `allow`
- `permission()` 属性可直给完整权限键数组（跳过拼接）
- 分隔符固定 `:`，与 `CacheUtils.COLON` 语义一致

## 通用授权：@Authorize / @PostAuthorize

指定某个 `Authorizer` Bean 执行自定义判断（多租户、数据归属等非角色/权限模型）：

```java
@Authorize(beanClass = TenantScopeAuthorizer.class)          // 或 beanName = "tenantScopeAuthorizer"
@GetMapping("/tenant/{id}")
public SimpleResponse<TenantVO> get(@PathVariable Long id) { ... }

@PostAuthorize(beanClass = OwnerCheckAuthorizer.class)       // 返回值参与判断（Post + 返回后校验）
@GetMapping("/order/{id}")
public SimpleResponse<OrderVO> order(@PathVariable Long id) { ... }
```

属性：`beanName`（按名）或 `beanClass`（按类），二选一。

## 主体注入：@Principal

```java
@GetMapping("/me")
public SimpleResponse<UserVO> me(@Principal LoginUser user) {   // LoginUser 是业务认证链路产出的主体类型
    return SimpleResponse.ok(convert(user));
}
```

- 注入类型由业务的认证链路决定（UserDetails 或自定义主体），框架不限定
- 不要从 `SecurityContextHolder.getContext().getAuthentication()` 手动取——静态调用丢可测性，用参数注入

## 匿名放行：@RequiresNonLogin

标记"仅未登录可访问"的端点（登录/注册页场景）；配合 `AnonymousRequestMatcher` 收集路径做白名单（见 filter-chain-and-advice.md）。

## 通用语义

- 注解可叠加（同种多次 + 跨种混贴），全部条件为 **AND**
- 注解支持元注解归并（自定义组合注解上的 `@RequiresAdminRole` 会被 `findMergedMethodAnnotation` 正确展开）
- 类级声明对该 Controller 全部方法生效；方法级可叠加收紧
