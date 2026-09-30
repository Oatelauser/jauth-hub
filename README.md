# 🛡️ jauth-hub

**对标 GitHub 的 OAuth 2.1 + OIDC 认证中心** —— 一套代码，既能独立部署成统一认证服务，也能内嵌进你的 Spring Boot 服务作为认证模块。

![Version](https://img.shields.io/badge/version-1.0.0-blue) ![Java](https://img.shields.io/badge/Java-21-orange) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.x-brightgreen) ![License](https://img.shields.io/badge/License-MIT-yellow) ![CI](https://img.shields.io/badge/CI-GitHub%20Actions-green)

---

## ✨ 为什么选 jauth-hub

| 💡 亮点 | 说明 |
|---|---|
| 🐙 **GitHub 式体验** | 授权确认页可勾选 scope、已授权应用看板一键撤销、PAT 个人访问令牌、`/me` 平台接口——用过的都说熟 |
| 🔀 **双模式，一套代码** | **独立部署**：起一个服务，所有项目接入它；**内嵌**：引一个 starter，认证能力长在你自己的服务里（宿主只欠一个 `UserDetailsService`） |
| 🔐 **安全内核先行** | 令牌落库只有 SHA-256 哈希（数据库泄露≠令牌泄露）、刷新令牌轮转 + **重放整族熔断**、强制 PKCE、签名密钥 90 天自动轮转 |
| 🎓 **天生教学** | 自带 `/demo` 教学区：对着**真实端点**完整走一遍授权码 + PKCE，每一步的 HTTP 请求/响应实时可见——前端同学看一遍就懂 OAuth 在干什么 |
| 🧩 **家族生态** | 与 [spring-plus](https://central.sonatype.com/search?q=io.github.oatelauser) 家族（统一响应/声明式鉴权/配置加密）开箱即用，也可完全脱离家族独立使用 |
| 🗄️ **零门槛起步** | 默认 H2 文件库（拉下来就能跑），生产切 PostgreSQL 一行配置，SQL 双方言兼容 |
| 🧪 **质量门禁** | 196 个测试 + 阿里 p3c 规约 + Spotless + SpotBugs/FindSecBugs + 端到端全流程测试，CI 强制全绿 |

## 📦 模块一览

```
jauth-hub-core                    协议与领域实现（存储/令牌/密钥/页面/SPI，零家族依赖）
jauth-hub-starter                 接管式自动配置（memory|jdbc 条件装配，引依赖即生效）
jauth-hub-selfservice             用户自助页（已授权看板、PAT 管理）
jauth-hub-resource-server-starter 资源服务器接入（内省校验 + 30s 缓存 + scope→权限映射）
jauth-hub-app                     独立部署壳（自带用户库 + /demo 教学区）
examples/embedded-demo            内嵌接入示例工程
```

## 🛠️ 环境搭建

| 依赖 | 版本 | 说明 |
|---|---|---|
| ☕ JDK | 21+ | `java -version` 确认 |
| 📦 Maven | 3.9+ | 或用 IDE 内置 |
| 🐘 PostgreSQL | 16+（可选） | 生产库；开发默认 H2 文件库，无需安装 |
| 🐳 Docker（可选） | 任意 | 仅跑 PG 集成测试用（CI 上自动） |

```bash
git clone https://github.com/Oatelauser/jauth-hub.git
cd jauth-hub
mvn verify   # 构建 + 全部测试 + 质量门禁
```

## 🚀 快速入门

### ① 三分钟起一个认证中心

```bash
mvn -pl jauth-hub-app -am package -DskipTests
java -jar jauth-hub-app/target/jauth-hub-app-1.0.0.jar
```

打开 <http://localhost:8080/demo> —— 教学区会带你走完 **登录 → 授权确认 → 换令牌 → 内省 → 调 API** 的完整闭环，每步 HTTP 明细实时可见。

首启自动创建超管（默认 local 档在 `application-local.yml` 可改，dev/prod 档见 `application-dev.yml` / `application-prod.yml`）：

```yaml
jauth-hub:
  bootstrap:
    superadmin:
      username: admin
      password: admin-dev-only-placeholder   # ⚠️ 生产必改，支持 ENC() 密文
```

### ② 新项目接入（二选一）

**内嵌模式**——认证长在你自己的服务里：

```xml
<dependency>
    <groupId>io.github.oatelauser</groupId>
    <artifactId>jauth-hub-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

```java
@Bean
UserDetailsService userDetailsService() {
    return username -> myUserService.load(username);  // 你只欠这一个 Bean
}
```

**资源服务器模式**——你的 API 校验 jauth-hub 签发的令牌：

```xml
<dependency>
    <groupId>io.github.oatelauser</groupId>
    <artifactId>jauth-hub-resource-server-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

```yaml
jauth-hub.rs:
  introspection-uri: http://auth-host:8080/introspect
  client-id: my-resource-server      # 在认证中心注册的机密客户端
  client-secret: ${RS_SECRET}
```

完整可跑示例见 `examples/embedded-demo`。

## 📖 用户操作手册

### 👤 终端用户

| 操作 | 路径 | 说明 |
|---|---|---|
| 🔑 登录 | `/login` | 用户名/密码（Passkey 规划中 v1.2） |
| ✅ 授权确认 | 授权跳转后的 consent 页 | 逐条勾选 scope，发的令牌只含你准许的权限 |
| 📋 已授权应用 | `/selfservice/apps` | 查看谁拿了你的授权，一键**解除授权** |
| 🔖 个人访问令牌 | `/selfservice/pat` | 勾选 scope + 有效期（30/90/365 天）生成长期令牌，**明文只显示一次** |

### 🛠️ 管理员

```bash
# 建普通用户（v1.0 管理页在 v1.1，先用 SQL；密码是 bcrypt 后的值）
INSERT INTO jauth_user (id, username, password_hash, display_name, role, status, created_at)
VALUES ('0192...', 'zhangsan', '{bcrypt}$2a$10$...', '张三', 'USER', 'ACTIVE', NOW());
```

```yaml
# 注册客户端（properties 播种，启动 upsert，幂等）
jauth-hub:
  clients:
    - client-id: my-webapp
      client-secret: "{noop}dev-secret"     # 生产换 bcrypt/ENC
      grant-types: [authorization_code, refresh_token]
      redirect-uris: [https://my-webapp.example.com/login/oauth2/code]
      scopes: [openid, profile]
```

### 💻 开发者（调用方）

```bash
# 授权码换令牌（公开客户端 + PKCE）
curl -X POST http://localhost:8080/oauth2/token \
  -d grant_type=authorization_code -d code=... \
  -d redirect_uri=... -d client_id=my-webapp \
  -d code_verifier=...

# 内省（资源服务器侧）
curl -u my-resource-server:$RS_SECRET \
  -d token=... http://localhost:8080/introspect

# 撤销
curl -u my-webapp:secret -X POST http://localhost:8080/oauth2/revoke -d token=...
```

OIDC 发现端点：<http://localhost:8080/.well-known/openid-configuration> （Spring 客户端零配置接入）

## ⚙️ 关键配置

| 配置 | 默认 | 说明 |
|---|---|---|
| `jauth-hub.storage` | `memory`（app 固定 `jdbc`） | 内存=轻量 demo；jdbc=生产 |
| `jauth-hub.issuer` | `http://localhost:8080` | 发行方地址 |
| `jauth-hub.educational` | `true` | 教学块开关（生产可关） |
| `jauth-hub.rate-limit.limit-per-hour` | `5000` | 按用户合并限流 |
| `jauth-hub.rate-limit.login-max-failures` | `5` | 连错锁 15 分钟 |
| `jauth-hub.cors.allowed-origins` | 空 | SPA 浏览器直连 token 端点时配 |

**默认策略**（每客户端可覆盖）：access 2h · refresh 30d 用后即轮转（重放→整族熔断）· PAT 90d · 授权码 5min+强制 PKCE · 密钥 90d 轮转+14d 重叠。⚠️ 框架防线：公开客户端不发 refresh token。

## 🗺️ 路线图

- **v1.1（平台层）**：组织 org、应用安装审批与权限封顶、应用/用户管理页
- **v1.2（强化层）**：Passkey 无密码登录、sudo mode 敏感操作二次认证
- **v2+**：webhook 事件、secret scanning、邮箱流……

## 📚 更多文档

- 📐 [架构规格 SPEC](docs/SPEC.md) —— 实施宪法（模块/表/端点/策略全量决议）
- 🗺️ [决策地图](.scratch/jauth-hub/map.md) —— 10 张决策票的来龙去脉
- 🔧 [贡献与质量门禁](AGENTS.md) · [发布流程](docs/RELEASE_PROCESS.md)

## 📄 License

MIT © 2026
