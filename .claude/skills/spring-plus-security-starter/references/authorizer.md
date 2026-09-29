# Authorizer SPI（权限数据源对接）

包：`io.github.oatelauser.springplus.security.authorization`。

## 机制总览

注解由 Spring Security 的 `AuthorizationManager` 体系消费：`CompositeAuthorizationManager` 聚合各 `Authorizer`，与 Spring Security 6/7 授权模型原生集成（方法级 + URL 级都走注解声明）。

- `Authorizer`：SPI 根接口——`AuthorizationResult verify(Authentication authentication, AnnotationMethodInvocation mi)`
- `AnnotationAuthorizer`（extends Ordered, Authorizer）：带 `getOrder()` 的注解驱动授权器基型
- 框架内置：`GrantedAuthorityAuthorizer`（默认，从 `Authentication.getAuthorities()` 取 `ROLE_*` / `source:action` 权限键比对）、`RequiresRoleAuthorizer`、`RequiresPermissionAuthorizer`
- 聚合器：`CompositeAnnotationAuthorizer` / `CompositeAnnotationPostAuthorizer` / `CompositeAuthorizationManager` / `CompositePostAuthorizationManager`

## 超管短路

持有 `ROLE_SUPER_ADMIN` 权限的用户**跳过全部具体校验直接放行**（`AnnotationAuthorizationDecision.ALLOW`）。语义：超管不是"拥有所有权限"，而是"授权体系对其豁免"——排查"为什么超管过了"时先想到这条。

## 对接业务权限数据源

权限/角色集合不在本模块，也不该写进 Controller。两条路：

**路线 1（默认，零代码）**：业务认证链路把权限装进 `GrantedAuthority`：

```java
// 业务的 UserDetailsService：角色与权限键一并装 authorities
return User.withUsername(username)
        .password(password)
        .roles("USER")                                  // → ROLE_USER，供 @RequiresRole 比对
        .authorities(List.of(new SimpleGrantedAuthority("user:delete")))  // 供 @RequiresPermission 比对
        .build();
```

**路线 2（自定义 Authorizer）**：权限存库/缓存/远程服务时，实现 `AnnotationAuthorizer` 注册为 Bean：

```java
@Component
public class DbBackedPermissionAuthorizer implements AnnotationAuthorizer {

    @Override
    public int getOrder() { return 100; }   // 链内优先级

    @Override
    public AuthorizationResult verify(Authentication authentication, AnnotationMethodInvocation mi) {
        Set<String> owned = permissionService.loadKeys(authentication.getName());   // 业务数据源
        // 与 mi 上的注解声明的权限键求交集判定；判定不了返回"不处理"让后续 Authorizer 接手
    }
}
```

规则：扩展走实现 SPI + 注册 Bean；**不要**改框架内置授权器，不要在授权器里做慢调用（授权每请求都走，数据源要快或加缓存）。

## 启动期校验（fail-closed）

- 空 `@RequiresRole(role = {})`、空 `source`/`action`：**启动直接失败**
- 注解声明了但解析出的权限集合为空：运行期拒绝（fail-closed，不静默放行）
