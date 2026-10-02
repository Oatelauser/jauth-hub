# D1 派单:账号状态变更的令牌/会话清剿 + status 挂 sudo(v1.3;主会话代执行版)

> 配额 kills subagent 期间由主会话照单执行;配额恢复后本单可直接移交 subagent。

## 语义(fail-secure,拍板口径)

| 触发 | oauth2 授权行(全 client) | token family | Spring Session | PAT |
|---|---|---|---|---|
| 自助改密(ProfileController) | 全删 | 全 BURNED | 全失效**保留当前会话**(改密者刚验旧密码+sudo,当前会话新鲜可信;GitHub 同款"登出其他会话") | 保留 |
| 管理员重置密码 | 全删 | 全 BURNED | 全失效(目标用户全部,不保留) | 保留 |
| 停用(toggleStatus→DISABLED) | 全删 | 全 BURNED | 全失效 | **全撤销**(账号死则凭据全死;启用方向不清剿) |

JWT 自包含 access token 的撤销盲区(opaque+内省为默认)在 javadoc 注明。

## 已核实接线事实

- 触发点:app `web/ProfileController#changePassword`(POST /api/profile/password,已有 @RequiresSudo,updatePasswordHash 后无任何清剿);app `web/AdminUsersController#resetPassword`(POST /api/admin/users/{id}/password,@RequiresSudo ✓);`#toggleStatus`(POST /api/admin/users/{id}/status,**无 @RequiresSudo ← 本批加**,停用即时挡登录经 AppUserDetailsService status→enabled)
- JDBC 撤销先例:`core/authorization/JauthJdbcOAuth2AuthorizationService` 烧族两步=先 `DELETE FROM oauth2_authorization WHERE principal_name=? AND registered_client_id=?` 后 `JdbcTokenFamilyService.burnFamily`(标 BURNED);本批做 per-principal 全量版
- 族谱双实现:core `token/JdbcTokenFamilyService` + `token/InMemoryTokenFamilyService`(无公共接口,镜像方法+互引 javadoc 是既有惯例);内存模式授权服务=starter `FamilyAwareInMemoryAuthorizationService`(final,持 authorizationIdsByFamily 索引,delegate 为框架内存实现)
- starter 装配:`JauthHubAutoConfiguration` 两分支(memory L942-950 FamilyAware 内联构造包 Auditing;jdbc L1092-1107 JauthJdbc 包 Auditing)——revoker bean 按分支注册
- 审计:core `audit/AuditEventPublisher`/`AuditEvent`/`AuditEventType`(AuditingOAuth2AuthorizationService 是用法范本;撤销两路径=save 置 INVALIDATED ∪ remove)
- 会话:app 恒 spring-session-jdbc(yml spring.session.jdbc.initialize-schema=never,表由 jauth Flyway V3 建)→ `FindByIndexNameSessionRepository` 按_principalName 索引可枚举;无该 bean 的宿主(ObjectProvider 空)优雅跳过
- PAT:`selfservice/pat/PatService`(Jdbc/InMemory 双实现;memory 模式 PAT 禁用);app 依赖 selfservice 可直接注入
- 测试范式:app 全上下文=SelfServicePageRenderingIntegrationTest(注解组合/独立 H2 库名/zh 钉死);控制器单测=直接调方法(C3 教训:standalone MockMvc 视图循环);sudo 门禁测试=SudoGatingIntegrationTest(A0515 断言形态照抄)

## 实施清单

1. core `token`:JdbcTokenFamilyService + InMemoryTokenFamilyService 各加 `burnAllByPrincipal(String principalName)`(镜像+javadoc 互引)
2. core `authorization`:`PrincipalAuthorizationRevoker` 接口(`int revokeAll(String principalName)`)+ `JdbcPrincipalAuthorizationRevoker`(DELETE per-principal + 烧全族;SQL 风格照烧族先例,双库兼容)
3. starter:FamilyAwareInMemoryAuthorizationService 加包内 `revokeAllByPrincipal`(族索引遍历→delegate.remove→烧全族);新 `InMemoryPrincipalAuthorizationRevoker`;JauthHubAutoConfiguration 两分支各注册 revoker bean(memory 分支 FamilyAware 提局部变量)
4. selfservice:`PatService` 加 `int revokeAllForUser(String userId)` 双实现
5. app 新 `AccountSecurityService`(编排:revoker + 会话失效 + PAT + 审计;方法 `onCredentialsChanged(principalName, userId, @Nullable keepSessionId)` / `onUserDisabled(principalName, userId)`;审计=新 AuditEventType 一条 `CREDENTIALS_REVOKED`,detail 带 reason(password_changed/user_disabled)与各级计数;老账⑧ D5 统一扩词表)+ `UserSessionInvalidator`(FindByIndexNameSessionRepository 枚举删除,keepSessionId 例外;无 bean=0+warn)
6. 接线三触发点(status 端点同步加 @RequiresSudo + javadoc 敏感操作注记)
7. 测试:core revoker/族谱单测;starter 内存 revoker 单测;app `CredentialRevocationIntegrationTest`(真库直插最小 oauth2_authorization/spring_session/jauth_pat 行→触发→断言删/BURNED/保留边界:当前会话留存、他人行不动)+ SudoGating 增 status 端点 A0515 用例 + Profile/AdminUsers 控制器单测补编排调用断言

## 禁做

- 不动协议端点/consent/框架行为;不动 rs-starter;不建 migration(纯用既有表);不引新依赖;README/SPEC 文档同步归 D6
- git 操作禁止(subagent 执行时)

## 验收

- `mvn verify` 全绿,测试数 ≥423 只增;spotless/p3c/SpotBugs 零违规
- 汇报 ≤20 行:文件清单(+/-)、verify 证据、口径偏差说明、临时文件
