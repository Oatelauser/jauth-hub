# Passkey 选型验证：Spring Security 7 原生 WebAuthn 能力边界（2026-10-01）

> SPEC §8 既定：先验 SS7 原生，不足再评 WebAuthn4J。本文为验证底稿，全部一手来源（官方 7.1 文档 = latest、Maven Central pom、本地依赖树/jar 实检）。

## 结论

**SS7 原生够用，采纳 `org.springframework.security:spring-security-webauthn`（BOM 统管 7.1.1），不引第三方 webauthn4j-spring-security，不裸用 webauthn4j-core。**

关键认知修正：SS 的"原生 WebAuthn"本质 = SS 的 DSL/过滤器/端点壳 + **WebAuthn4J 核心验证**（`webauthn4j-core:0.31.9.RELEASE` 为其 compile 传递依赖）。所以选型真命题不是"SS vs WebAuthn4J"，而是"用 SS 的壳（SPI 适配即可）还是自己写壳"——SS 的壳已覆盖 jauth-hub v1.2 全部需求，自写无收益。

## 事实清单（已核实）

### 1. 模块拆分与版本管理（本地 jar/pom 实检）

- SS 7.1.1 的 `spring-security-web` jar 中 webauthn 相关条目仅 1 个：前端脚本 `org/springframework/security/spring-security-webauthn.js`；**零 Java 类**
- Java 实现在独立 artifact `spring-security-webauthn`（Central 上 7.0.0 起，7.1.1 为当前 release，7.2.0-M2 在途——活跃维护）
- **spring-security-bom 7.1.1 统管其版本**（本地 BOM pom 实检）→ Boot 4.1.1 BOM 链下加依赖**不写版本号**，不违反 SPEC §1"依赖不单独锁版"
- `webauthn4j-core:0.31.9.RELEASE` 由其传递引入，版本由 SS 模块钉死，我们不碰

### 2. DSL 与端点面（官方 7.1 文档）

```java
http.formLogin(...).webAuthn(w -> w.rpName(..).rpId(..).allowedOrigins(..));
```
（可选扩展点：`creationOptionsRepository`、`messageConverter`；与 formLogin 共存不互斥）

| 端点 | 方法 | 鉴权 | 说明 |
|---|---|---|---|
| `/webauthn/register/options` | POST | 需认证 + CSRF | 出挑战 |
| `/webauthn/register` | POST | 需认证 + CSRF | body = `navigator.credentials.create` 结果 + `label`；响应 `{success:true}` |
| `/webauthn/authenticate/options` | POST | permitAll + CSRF | challenge 存 session |
| `/login/webauthn` | POST | permitAll + CSRF | 认证断言；响应 `{redirectUrl, authenticated}`（JS 侧跳转） |

### 3. 持久化 SPI（官方 7.1 文档 + API）

- `PublicKeyCredentialUserEntityRepository` + `UserCredentialRepository`（包 `org.springframework.security.web.webauthn.management`），暴露 bean 即接管，默认内存实现
- 官方 `JdbcUserCredentialRepository` 的 `user_credentials` 字段面（对齐用）：
  `user_entity_user_id, credential_id, public_key, public_key_credential_type, signature_count, created, last_used, label, backup_eligible, backup_state, uv_initialized, authenticator_transports, attestation_object, attestation_client_data_json`
- **对照现表 `jauth_user_credential`（V2 建）**：缺 `label`、`public_key_credential_type`、`backup_eligible`、`backup_state`、`uv_initialized`、`authenticator_transports`、`attestation_object`、`attestation_client_data_json` 共 8 列 → **V8 迁移补列**（含 attestation 双 BLOB 的 H2/PG 双兼容写法）
- `PublicKeyCredentialUserEntity` 适配 `jauth_user`：user handle 用 `jauth_user.id`（UUIDv7 per-user 随机，防跨站关联可接受）

### 4. 页面与 JS（官方 7.1 文档 + 实战指南）

- 框架提供参考实现登录页/注册页（含 JS 逻辑）；自定义页面**自带 JS**（官方立场"typically done using JavaScript"，参考默认页写法自写 fetch 流，零 CDN 外链符合 SPEC §1）
- 7.1 文档**无 conditional UI（autofill/autocomplete）专项支持** → v1.2 走按钮式显式流程，不做条件 UI（YAGNI，留 v2+）
- 实战坑（weareyuma.com 指南，Boot 4.x 实测）：凭据被删后旧 options 的 `allowCredentials` 已失效，断言报 **404 UserNotFoundError** → 前端 catch 后重走 options；Boot 自动配置会介入，我们自有链装配时可显式接管（C1 验证点）

### 5. 会话与 sudo 兼容性

- challenge 默认存 HttpSession → Spring Session JDBC 兼容
- `/login/webauthn` 成功走标准 SessionAuthenticationStrategy（changeSessionId：**换 id 不换 session 对象，属性保留**）→ 已认证会话中途 POST 断言 = 就地升权，`UserRepository.updateStrongAuthAt`（v1.0 已建）打点 → **sudo mode 技术路径成立**

### 6. 成熟度

6.4 引入（当时标 pre-release）→ 7.1（current latest）文档整页**无实验性警告**，端点/SPI/JDBC 适配器齐备成文，7.2 持续迭代。可按生产级采用。

## 能力边界与风险点（派单词必须携带）

1. **principal/authorities 形状（最大集成风险）**：passkey 登录成功后的 `Authentication.principal` 需与 `AppUserDetails`（B12 AppUserDetailsService）对齐，否则下游 `@Principal`/强转全崩。C1 验收必测项：passkey 登录后 /profile、selfservice 页面全走通
2. **rpId/allowedOrigins 必须显式配置**：`jauth-hub.passkey.*` 属性化（enabled 默认 false；关闭时零字节影响，`@ConditionalOnProperty`）
3. **嵌入模式**：webAuthn() 只挂 jauth 协议链（`jauthProtocolSecurityFilterChain`），宿主链不碰
4. **双库**：attestation BLOB 的 H2(PG 兼容模式)/PG 双方言；H2 对 CHAR(36) 空格回补前科（owner 列 trim 已收）→ user_id 列读取注意
5. **算法参数**：pubKeyCredParams 默认集（ES256/RS256 等）C1 实施时对 `WebAuthnConfigurer` 7.1.1 源码复核，不自定义
6. **memory 模式**：SS 默认内存实现不能直接用（需适配 jauth_user），InMemory 双实现照既有 memory|jdbc 条件注册模式自写

## 对比方案否决理由

| 方案 | 否决理由 |
|---|---|
| `webauthn4j-spring-security`（第三方 starter） | 版本滞后 Boot 4/SS7，等于用旧壳包同一核心，反向收益 |
| 裸 `webauthn4j-core` | 自写全部 ceremony 接线（挑战管理/CSRF/端点/会话），SS 已给全套，纯浪费 |

## 来源

- 官方参考（7.1 = latest）：docs.spring.io/spring-security/reference/servlet/authentication/passkeys.html
- Maven Central：spring-security-webauthn 7.1.1 pom（webauthn4j-core 0.31.9.RELEASE compile）
- 本地实检：spring-security-web-7.1.1.jar 条目、spring-security-bom-7.1.1.pom、`mvn dependency:tree`（core 模块 SS 全家 7.1.1）
- 实战指南：weareyuma.com "Spring Security 7 and passkeys: a practical guide"（Boot 4.x，JDBC schema/端点/404 坑）
