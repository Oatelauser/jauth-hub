---
name: spring-plus-boot-starter
description: spring-plus-framework 家族基础模块使用约定（坐标 io.github.oatelauser:spring-plus-boot-starter）。覆盖：ApiClient 声明式 HTTP 客户端（builder 多客户端、拦截器链、重试、GZIP、Micrometer api.client.requests 指标、AuthProvider 认证注入、SSRF 防护 spring-plus.client.ssrf.*）、ENC() 配置文件加密（ConfigEncryptor 命令行、ConfigCipher 密钥）、优雅停机（SmartGracefulShutdownHandler/ShutdownHook/StartupProcess）、boot.utils 通用工具（ApplicationContextHolder/ApplicationContextUtils/BeanUtils/AnnotationUtils/FileResources/LogSanitizer）。封装下游 HTTP 调用、加密数据库密码等敏感配置、处理启动/停机顺序、排查 ApiClient 超时重试与 SSRF 拦截问题时使用。统一响应/异常在 spring-plus-web-starter；Redis 工具在 spring-plus-redis-starter。
---

# spring-plus-boot-starter

这个 skill 直接给 AI 使用。本文件是导航与全局规则；API 细节与示例在 `references/`。

**模块定位**：家族基础模块（无内部依赖；web/security 都依赖它）+ Boot 生态装配工具域。它**不是**全框架统一装配层——响应/异常在 web，幂等在 governor，鉴权在 security，Redis 工具已拆至 redis（ADR 0003）。

## 按需加载参考文档

| 任务涉及 | 加载 |
|---|---|
| HTTP 客户端：ApiClient 装配/多客户端、ApiRequest/ApiResponse、AuthProvider、重试/GZIP/日志/指标、SSRF 防护 | [references/http-client.md](references/http-client.md) |
| 配置加密：ENC() 书写、ConfigEncryptor 命令行生成密钥密文、解密时机 | [references/config-encryption.md](references/config-encryption.md) |
| 优雅停机：StartupProcess 启动序、ShutdownHook 停机序 | [references/lifecycle.md](references/lifecycle.md) |
| boot.utils 通用工具逐类速查（含 LogSanitizer 敏感键掩码） | [references/utils.md](references/utils.md) |

## 与原生 Spring Boot 的关系（使用规则总纲）

本模块**不替换**任何 Boot 机制，提供四个工具域（HTTP 客户端门面 / 配置加密 / 启停编排 / 通用工具），替代的是各自的手写惯用法：

| 手写惯用法 | 本模块写法 |
|---|---|
| 裸用 `RestClient`/`RestTemplate` 调下游 + 自己加重试/日志/指标 | `ApiClient`：单下游配 `spring-plus.client.base-url` 即得默认 Bean；拦截器链/重试/GZIP/`api.client.requests` 指标开箱 |
| 手写拦截器给每个请求加 token/签名 header | 实现 `AuthProvider` 注册为 Bean，一处注入处处生效 |
| jasypt / 手写属性后处理解密敏感配置 | `ENC(密文)` + `ConfigEncryptor` 命令行生成密钥密文（解密在 EnvironmentPostProcessor 阶段，早于一切 Bean） |
| `ApplicationRunner`/`CommandLineRunner` 里做缓存预热 | `StartupProcess`（`start()` 在 **Web 容器接受请求前**执行；Runner 是启动后——按语义选，别混用） |
| `Runtime.addShutdownHook` / `@PreDestroy` 里关资源 | 实现 `ShutdownHook` Bean（phase 机制保证 Web 容器摘流量后才执行，升序、异常不中断） |
| 手写静态 `ApplicationContextHolder` / 反射工具 / 资源加载 / 日志脱敏 | `boot.utils` 现成：`ApplicationContextHolder` / `AnnotationUtils` / `FileResources` / `LogSanitizer` 等 |
| 自建 Spring 容器工具（按泛型找 Bean、Ordered 排序收集） | `BeanUtils.getBeanOfGenerics` / `BeanUtils.sort(...)` |

以下照常用，本模块不接管：

- Boot 官方配置与机制（Actuator、profiles、`spring.http.client.*` 等官方键）不受影响
- `spring-boot-starter-web`/`webmvc` 等官方 starter 照常引入；ApiClient 基于 Boot http-client 抽象，httpclient5 为 provided 可选引擎（缺席自动降级 JDK 实现）
- 不需要的能力不必引入：本模块 Bean 均带条件装配（如不配 `base-url` 不装配默认客户端）

## 核心决策规则

1. 单一下游服务：配 `spring-plus.client.base-url` 即得默认 `ApiClient` Bean（缺省不装配）
2. 多下游服务：一律 `ApiClient.builder()` 自建 Bean，不让自动配置装多个
3. 认证注入：实现 `AuthProvider` 注册为 Bean，不在每个请求上手写 header
4. 反射 / 容器访问 / classpath 资源 / 日志脱敏等通用工具用 `boot.utils`，不自写
5. 敏感配置（数据库密码、第三方 secret）写 `ENC(密文)`，环境准备阶段自动解密

## 红线

- `uri` / `queryParam` **禁止拼接外部输入**（SSRF 主路径）；回调/Webhook/抓取类调用必须开 `spring-plus.client.ssrf.*` 并配置 allowlist
- `spring-plus.client.ssl.allow-insecure`（信任所有证书）仅限内网调试，生产禁用
- `InsecureTlsHelper` 已 `@Deprecated`：仅测试联调，业务代码禁止直接引用（生产使用视同漏洞）
- `ShutdownHook` 不注册为普通 `@PostConstruct` 逻辑（停机顺序由 handler 保证，见 lifecycle.md）

## 文档同步约定

本 skill 的 API 断言以模块源码为唯一基准；模块行为变更时，模块 README 与本 skill（SKILL.md 及 references/）必须同步修改——只改一边视为未完成。
