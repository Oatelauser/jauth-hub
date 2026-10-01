# C1 施工单:Passkey 核心接线(core/starter)——v1.2 批次 1/5

> 你是施工 subagent。项目根 `D:\workspace\CC\jauth-hub`(Windows,PowerShell/mvn 可用)。
> 必读:本单全文 + AGENTS.md(质量门禁/hook 约定)。底稿:`.scratch/jauth-hub/research/03-passkey-selection.md`(选型证据,勿重研)。
> **铁律:禁止任何 git 操作;只做本单范围;写码后 `mvn verify` 必须全绿;发现本单事实与实际冲突 → 停下汇报,不自行改决议。**

## 1. 任务一句话

把 Spring Security 7 原生 WebAuthn(passkey)以**默认关闭**的开关接进 jauth-hub 协议链:依赖、属性、V8 迁移、存储双实现、链装配、审计——为 C2(页面)/C3(sudo)备地基。**本批无任何 HTML/JS。**

## 2. SPEC 摘录(宪法相关条,冲突即停)

- §1:Boot 4.1.1 BOM 统管版本,**依赖不单独锁版**;前端无 CDN 外链(本批不涉及)
- §2:接管式自动配置;`memory|jdbc` 条件注册(`jauth-hub.storage`);协议链**无 anyRequest 兜底**,序位默认 100;嵌入模式永不建 catch-all 链
- §3:主键 CHAR(36)=UUIDv7 字符串;词表归代码枚举,DB 不加 CHECK;审计**只记生命周期事件**;`jauth_user_credential` v1.0 建表 v1.2 启用
- §5:v1.2 强化层:Passkey **开关,默认关**
- §6:登录防爆破等默认策略(本批不新增)
- §8:选型已定——SS7 原生 `spring-security-webauthn`(research/03,2026-10-01)

## 3. 框架事实(主会话已源码级核实,**直接采信**)

1. **依赖**:`jauth-hub-core/pom.xml` 加 `org.springframework.security:spring-security-webauthn`(**不写版本**——spring-security-bom 7.1.1 统管,Boot 4.1.1 链传递);传递引入 `webauthn4j-core:0.31.9.RELEASE`。WebAuthn 类在 `org.springframework.security.web.webauthn.*`;DSL configurer 在 spring-security-config 的 `org.springframework.security.config.annotation.web.configurers.WebAuthnConfigurer`
2. **SPI(两接口各 4 方法,源码实锤)**:
   - `PublicKeyCredentialUserEntityRepository`:`findById(Bytes)` / `findByUsername(String)` / `save(PublicKeyCredentialUserEntity)` / `delete(Bytes)`
   - `UserCredentialRepository`:`delete(Bytes credentialId)` / `save(CredentialRecord)` / `findByCredentialId(Bytes)` / `findByUserId(Bytes)`
3. **Provider 行为(源码实锤,7.1.1)**:`WebAuthnAuthenticationProvider.authenticate()` → 验签 → `userEntity.getName()` → **`userDetailsService.loadUserByUsername(username)`**(宿主 UserDetailsService 被咨询!)→ authorities = UserDetails 的 authorities + `FactorGrantedAuthority.WEBAUTHN_AUTHORITY` → `new WebAuthnAuthentication(userEntity, authorities)`。即:**principal = PublicKeyCredentialUserEntity(非 UserDetails);authorities 含角色 + WEBAUTHN 因子权威**
4. **端点(官方 7.1 文档)**:POST `/webauthn/register/options`(需认证)、POST `/webauthn/register`(需认证,body 含 label)、POST `/webauthn/authenticate/options`(放行,challenge 存 HttpSession)、POST `/login/webauthn`(放行,成功响应 JSON `{redirectUrl, authenticated}`)
5. `CredentialRecord` 全字段(对齐官方 JdbcUserCredentialRepository 14 列):credentialId、userEntityUserId、publicKey(PublicKeyCose)、signatureCount、uvInitialized、username、backupEligible、backupState、transports、attestationObject、clientDataJSON、label、created、lastUsed
6. SS 自带 `Map*` 内存实现可作参考但**不能直接用**(用户面必须适配 jauth_user)
7. 补充发现:`spring-security-config` 有 `WhenWebAuthnRegisteredMfaConfiguration` 授权谓词(C3 备用,本批不碰)

## 4. 本仓库接线点(已核实 file:line)

- 协议链:`jauth-hub-starter/.../JauthHubAutoConfiguration.java:569` `jauthProtocolSecurityFilterChain`——securityMatcher 为 OrRequestMatcher(601-607,webauthn 路径需**条件加入**)、authorizeHttpRequests 逐路径显式(620-626)、formLogin(628)、**CORS 条件启用范式(616-618,passkey 照此 if 形态)**
- 属性:`JauthHubProperties.java`(嵌套静态类 + 防御性拷贝范式,照抄 Cors/RateLimit 风格)
- 用户域:`core/user/UserRepository.java`(`findByUsername`/`findById` 齐);`JauthUser` record(id=UUIDv7 字符串、username、role、status、strongAuthAt…)
- 双实现注册范式:JauthHubAutoConfiguration 内嵌套配置类按 storage 条件分注(InMemory ~:789,Jdbc ~:947,`new JdbcUserRepository(new JdbcTemplate(dataSource))` 形态)
- 迁移纪律:V7=单条 ALTER ADD COLUMN 带 DEFAULT(双兼容先例);**二进制列 = Base64 TEXT(V4 jwk 先例,不用 BLOB)**;下一号 **V8**;既有 `FlywayMigrationTest`/`PostgreSqlMigrationTest`(Testcontainers)会自动续跑
- 审计:`core/audit/AuditEventType`(枚举加点分小写 wireName)+ `AuditEventPublisher`;登录事件走 `SecurityEventAuditBridge`(Spring Security 事件桥)
- **principal 下游消费面(全库 grep 实锤仅一处)**:`core/token/ContributedClaims.java:48` `context.getPrincipal()`——必须兼容 WebAuthnAuthentication;其余无 `@AuthenticationPrincipal` 强转
- 表现状:V2 的 `jauth_user_credential` 现有 7 列(id/user_id/credential_id/public_key/sign_count/created_at/last_used_at)

## 5. 实施清单

**A. V8 迁移** `V8__passkey_credential_columns.sql`:单条 ALTER 补 8 列——`label VARCHAR(100) DEFAULT NULL`、`credential_type VARCHAR(32) NOT NULL DEFAULT 'public-key'`、`backup_eligible BOOLEAN NOT NULL DEFAULT FALSE`、`backup_state BOOLEAN NOT NULL DEFAULT FALSE`、`uv_initialized BOOLEAN NOT NULL DEFAULT FALSE`、`transports VARCHAR(256) DEFAULT NULL`(逗号连接序列化)、`attestation_object TEXT DEFAULT NULL`(Base64)、`attestation_client_data_json TEXT DEFAULT NULL`(Base64)。头注释写"为什么"(CredentialRecord 字段面 + Base64 TEXT 双兼容纪律引 V4 先例)

**B. core 新包 `io.github.oatelauser.jauth.core.passkey`**:
- `JauthUserEntityRepository` implements PublicKeyCredentialUserEntityRepository:适配 `UserRepository`;**user handle = `jauth_user.id`(UUIDv7 字符串)的 UTF-8 `Bytes`**;findById→按 id 查、findByUsername→按 username 查,displayName 回退 username;`save`/`delete` 为 no-op + Rationale 注释("用户真源在 jauth_user,WebAuthn 注册不建用户;框架若调用仅涉用户句柄登记")——save 是否真被框架调用,以 IDE 源码核实后在注释中写明调用方
- `CredentialRecordRowMapper`(或等价转换器):行 ↔ `ImmutableCredentialRecord`,Base64 编解码 attestation 两列;transports 逗号拆合
- `InMemoryPasskeyCredentialRepository` / `JdbcPasskeyCredentialRepository` implements UserCredentialRepository:全 4 方法;jdbc 读写 15 列(7 旧 + 8 新);**`save`/`delete` 内发布审计事件**(注册走框架 filter,仓储包装是审计挂点)
- 契约测试 `AbstractPasskeyCredentialRepositoryContractTest`(两实现同契约):save→findByCredentialId/findByUserId 回读字段全等、delete 后查空、attestation Base64 回转

**C. starter 装配**:
- `JauthHubProperties` 加嵌套 `Passkey`:`enabled=false`、`rpId=null`、`rpName=null`、`allowedOrigins=空 List`(防御性拷贝照既有范式)
- 条件注册 repo bean:`passkey.enabled=true` × `storage∈{memory,jdbc}` 四象限(嵌套配置类,`@ConditionalOnMissingBean` 可覆盖,照用户仓储范式)
- 协议链内 `if (properties.getPasskey().isEnabled())`:①securityMatcher 增加 `/webauthn/**` 与 `/login/webauthn` 两 matcher(注意 `/login` 既有 matcher 不匹配子路径);②authorize:`/webauthn/authenticate/options`+`/login/webauthn` permitAll,`/webauthn/register/options`+`/webauthn/register` authenticated(仍无 anyRequest 兜底);③`http.webAuthn(w -> ...)` 配 rpId/rpName/allowedOrigins——**未显式配时从 `jauth-hub.issuer` 推导**:rpId=issuer 的 host、allowedOrigins=[scheme://host:port]、rpName 默认 `"jauth-hub"`;issuer 不可解析且 rpId 未配 → 启动 fail-fast 带清晰报错
- WebAuthnConfigurer 的 bean 发现细节(是否自动从 ApplicationContext 取我们的 repo bean、UserDetailsService 如何注入)以 IDE 源码核实后接线,不得靠猜

**D. 审计**:`AuditEventType` 加 `PASSKEY_REGISTERED("passkey.registered")`、`PASSKEY_REMOVED("passkey.removed")`;核实 `SecurityEventAuditBridge` 是否自动覆盖 passkey 登录成功/失败(若 WebAuthnAuthenticationFilter 走标准事件线则已覆盖,**不**另加登录词,汇报中写明结论)

**E. ContributedClaims 兼容**:`getPrincipal()` 读取处兼容两种 Authentication 形态(表单登录=AppUserDetails / passkey=WebAuthnAuthentication),claims 语义不变;Rationale 注释引本单第 3.3 条

**F. 测试**(在相关模块,遵循既有测试基建):
1. **零影响**:默认关时既有全量测试不动即绿;新增断言:关时 `/webauthn/register/options` 不在链上(404)
2. 开+memory:上下文起、CSRF 下 POST `/webauthn/authenticate/options` 得 challenge JSON
3. 开+jdbc:V8 迁移 H2 过;PG Testcontainers 契约续跑
4. **principal 对齐(本批核心验收)**:优先用 `com.webauthn4j:webauthn4j-test`(test scope,版本 0.31.9.RELEASE 与传递 core 对齐)合成注册→认证回路:注册成功→仓储有行+审计发事件;认证成功→Authentication authorities 含角色与 WEBAUTHN 因子、`getName()`=username;**若该测试件不可用,降级为 WebAuthnRelyingPartyOperations 打桩的 provider 层测试 + 端点冒烟,汇报中明示降级理由(不许静默降级)**

**G. 不动**:README/docs(C2 一并写用户面);AppErrorCode(C2);examples;rs-starter

## 6. 明确不做(越界即停)

页面/JS(C2)、sudo 与 strong_auth_at 打点(C3)、@RequiresScope(C4)、错误码新词(C2)、改动 spring-plus 家族件

## 7. 验收标准(主会话逐条对)

1. `mvn verify` 全模块绿(含 p3c/Spotless/SpotBugs 三门禁);测试数较 341 只增不减
2. 默认关闭零影响有测试为证
3. V8 双库迁移绿
4. principal 对齐测试在(或汇报注明降级理由)
5. 汇报格式:**正文 ≤ 40 行**:改动文件清单(新增/修改分列,每文件一行职责)+ 测试数变化 + mvn verify 尾部证据 + 降级项(若有)+ 遗留风险(若有)
6. 临时脚本/草稿不留仓库;**不做 git 操作**
