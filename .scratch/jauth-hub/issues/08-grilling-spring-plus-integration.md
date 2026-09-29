# 08 spring-plus 三件套对接落点

Type: grilling
Status: resolved
Blocked by: 09

## Question

对接用户已发布到 Maven Central 的三个扩展（`io.github.oatelauser:spring-plus-*`，项目内有 skill 说明书：`.claude/skills/`），决定各自在五模块中的引入方式与落点：

- **spring-plus-web-starter**（SimpleResponse/PageResponse、00000/A0/B0/C0 状态码、三协议全局异常、@RestControllerAdvice @Order 段位）：app/selfservice 的管理接口用它渲染？与票 09 的响应 SPI 如何衔接（作为 SPI 的默认实现之一）？
- **spring-plus-security-starter**（@RequiresRole/@RequiresPermission/@Authorize、Authorizer SPI、@Principal、SUPER_ADMIN 短路、SecurityExceptionAdvice 401/403）：给 jauth-hub 自身管理接口（用户管理/应用管理/安装审批）做声明式鉴权；其 SUPER_ADMIN 语义与 `jauth_user.role(SUPERADMIN)` 的映射；SecurityFilterChain denyAll 红线与 03 的宿主链共存规则如何相容
- **spring-plus-boot-starter**（ENC() 配置加密、ApiClient、优雅停机、boot.utils）：ENC() 用于 client_secret 等敏感配置落盘加密；demo 区调受保护接口用 ApiClient？还是裸 RestClient
- **关键架构问题**：jauth-hub 是通用库——spring-plus 依赖放哪层？core/starter 若强依赖会污染不用 spring-plus 的宿主；候选：仅 app 模块必选 + core 对 web/security 做 optional 编译依赖 + 宿主自行选择
- 版本线：spring-plus 家族与 Boot 4.1/Spring Security 7 的兼容矩阵确认（用户自家件，需给版本号）
- 原生镜像约束（复审已裁 v1 JVM-only）：三件套是否自带 GraalVM RuntimeHints 仅作记录不阻塞 v1；app 代码保持 AOT 友好，v1.x 再启用原生（手册 docs/GRAALVM_NATIVE_IMAGE_SUPPORT.md）

产出：三件套 × 五模块的引入矩阵（必选/可选/不用）+ 各落点决定。

## Answer

**引入矩阵（Q1 照推荐）**

| 模块 | boot | web | security |
|---|---|---|---|
| core | ✗ | ✗ | ✗ |
| starter | ✗ | 可选（`@ConditionalOnClass` 检测到时自动注册 SimpleResponse 版 `ResponseRenderer`，宿主自定义 Bean 仍优先） | ✗ |
| selfservice | ✗ | ✗（控制器一律走 09 的 SPI） | ✗ |
| app | ✓ ENC()/优雅停机/boot.utils | ✓ SimpleResponse 渲染、三协议异常 | ✓ `@Requires*` 管理接口鉴权 |
| resource-server-starter | ✗ | ✗ | ✗ |

**三个已核实事实（免裁决）**
1. denyAll 红线无冲突：家族要求"业务自配安全链"，jauth-hub-app 本就自配、管理链默认 denyAll，天然合规。
2. 协议端点与 `SecurityExceptionAdvice` 互不干扰：OAuth2 协议错误在过滤器链内即被框架转为 RFC JSON，不会流入 advice；advice 只接管理接口 MVC 层异常（正是 A02xx 渲染目标）。
3. SUPER_ADMIN 映射：app 模式加载 `jauth_user.role=SUPERADMIN` 时向 authorities 附加 `ROLE_SUPER_ADMIN`，家族超管短路即生效；管理接口鉴权走 GrantedAuthority 路线（零代码），DB 数据源 Authorizer 留 v2。

**其余裁定**：demo 区 HTTP 客户端用**裸 `RestClient`**（用户裁定，不引 ApiClient）；app 的 `application.yml` 敏感项（数据库密码、超管初始密码）默认 **ENC() 密文**；错误码渲染衔接由 starter 的可选自动配置承担（见矩阵）。

**版本线（用户给定）**：spring-plus 家族 **1.1.0** 兼容 Boot 4.1 / Spring Security 7——06 锁依赖仲裁时采用；三件套是否带 GraalVM RuntimeHints 仅记录不阻塞（v1 JVM-only 已裁）。
