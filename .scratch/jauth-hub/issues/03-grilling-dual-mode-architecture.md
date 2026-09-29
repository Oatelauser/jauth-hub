# 03 双模式（内嵌/独立）架构与模块划分

Type: grilling
Status: resolved
Blocked by: 01

## Question

用户已定：jauth-hub 同一套代码既要能**独立部署**（自带用户库、登录/授权页、管理页），也要能**内嵌**进宿主服务作为其认证模块（宿主提供用户体系）。基于票 01 的 SAS 扩展点研究，决定：

- 模块划分：core 库 / starter（自动配置）/ app（独立部署壳）怎么切，边界在哪
- 嵌入模式的接入面：宿主插自己的 UserDetailsService？库表归属（SAS 的 oauth2_authorization consent 表挂宿主库还是独立 schema）？
- 登录页/Consent 页在两种模式下分别由谁渲染
- 与 Spring Boot 4 自动配置机制的配合方式

产出：模块结构图 + 每个模块职责一句话 + 嵌入模式接入契约（宿主需要提供什么）。

## Answer

模块结构（四模块，Q1=C）：

```
jauth-hub/
├── jauth-hub-core                        # 领域与协议实现，无自动配置、无 Spring Boot 依赖耦合
│   ├── client/authorization/consent      # RegisteredClientRepository / OAuth2AuthorizationService /
│   │                                     #   OAuth2ConsentService 的内存 + JDBC 双实现（JDBC 表带租户列，
│   │                                     #   需子类化框架 Jdbc*Service 定制 SQL——已接受的代价）
│   ├── token/                            # opaque/JWT 定制、刷新轮转、JWK 生成与轮转调度
│   ├── web/                              # 默认 Thymeleaf 登录/consent/设备验证页 + 控制器
│   ├── org/                              # 组织实体、org 上下文（单 issuer，无租户路由）
│   └── resources/flyway                  # 迁移脚本（SAS 标准表 + org 归属列 + PAT/审计等扩展表，H2/PG 双兼容）
├── jauth-hub-starter                     # 自动配置：opt-in 接管装配（Q2=A），按 jauth-hub.storage
│                                         #   条件注册 memory|jdbc；一切 Bean 内置默认 + 可覆盖
├── jauth-hub-app                         # 独立部署壳：自持用户库、用户管理页、示例种子、/demo 教学区
└── jauth-hub-resource-server-starter     # 资源服务器接入薄封装：预接线 introspection 校验 + scope→权限映射

（05 修订：新增第五模块 jauth-hub-selfservice——看板/PAT/应用管理等域数据自助页，app 依赖、嵌入宿主可选依赖；用户管理页留在 app。）
```

决议（1–5、8 为终判；6 经全局复审改判，见下）：

1. **四模块**如上；rs-starter 是薄封装，复杂校验逻辑仍是标准 Spring Security。
2. **接管式自动配置**（Q2=A）：引入 jauth-hub-starter 即 jauth-hub 装配链生效，Boot 4 自带的内存属性配置让位；不引入则 Boot 默认行为不动。
3. **存储双实现、条件注册**（Q5 用户裁定）：`jauth-hub.storage=memory|jdbc`。内存面向轻量嵌入（demo/小型服务，重启丢授权状态），JDBC 面向生产与独立部署。properties 播种（`jauth-hub.clients[n].*` 启动 upsert 进活动仓库）两种模式都可用；运行时真源 = 当前激活的仓库实现，无双轨事实。
4. **嵌入契约**（Q3 用户裁定：一切皆可替换）：宿主**必须**提供 `UserDetailsService`；jdbc 模式还须提供 `DataSource`。其余组件（页面模板、PasswordEncoder、token 定制、密钥源…）全部内置默认，宿主可用标准 `@ConditionalOnMissingBean` 语义或 Thymeleaf classpath 模板覆盖逐个替换。
5. **库表归属**（Q4=A）：挂宿主主 DataSource，沿用框架标准表名（oauth2_*），Flyway 随 core 分发；不做表前缀。
6. ~~多租户 v1 全运行时~~ **改判（全局复审，用户裁定 Q1=A）：单 issuer + 组织(org)维度**。GitHub 式开放平台 = 单 issuer、全网一个用户池、org 作权限边界（应用归属 org、权限受 org 约束），一个用户可属多个 org。多用户池隔离需求由**多部署**满足（N 个池 = N 次独立部署；内嵌模式天然每宿主一池），不做进程内多租户；若产品演进为"认证云服务"（Auth0 形态）再立独立 effort 补 issuer 路由层，届时 org/归属列已就位。
7. **表手术随之收窄**：仅 `oauth2_registered_client` 需加 org 归属列（子类化 `JdbcRegisteredClientRepository`）；authorization/consent 经 client 联查 org；自有扩展表（PAT/审计/看板）原生带 org 键。
8. **SQL 方言（用户裁定）**：H2 + PostgreSQL 双兼容（H2 跑 PostgreSQL 兼容模式），不承诺 MySQL。

已知连锁影响（供 04 排期权衡）：org 归属 + 应用安装到 org 的权限边界是新增工作面（对标 GitHub Apps installations；研究票 02 判"缓"，04 重新排期）。
