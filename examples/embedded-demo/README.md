# embedded-demo：把 jauth-hub 内嵌进你的 Spring Boot 应用

一个不依赖 `jauth-hub-app` 的独立 Boot 应用：引入 `jauth-hub-starter` 即获得完整认证中心（memory 存储、
零数据库），同时演示**宿主自家业务接口自己接 OAuth 保护**——经 `jauth-hub-resource-server-starter` 指向
自身的内省端点验证 opaque token（06 Q1 裁定的闭环模式）。

运行：`mvn -pl examples/embedded-demo -am spring-boot:run`（默认 8081 端口，账号 `user` / `demo-user-password-placeholder`）。

## 五步接入（这就是本示例的全部代码量）

1. **引 starter**：`pom.xml` 加 `io.github.oatelauser:jauth-hub-starter` —— 接管式装配生效，Boot 4 自带
   内存配置让位，协议链（/oauth2/authorize、/token、/introspect、/login、consent 页…）全部就位。
   要保护自家接口再加 `jauth-hub-resource-server-starter`。
2. **供 UserDetailsService**（嵌入契约唯一硬性要求）：见 `DemoSecurityConfiguration#demoUserDetailsService`，
   示例为 1 个用户的 `InMemoryUserDetailsManager`；正式项目接你的用户库。**同时**把 `PasswordEncoder` 换成
   `PasswordEncoderFactories.createDelegatingPasswordEncoder()`——框架端按 `{bcrypt}` 前缀校验 client_secret，
   裸 bcrypt 哈希会让内省等 HTTP 凭证校验直接抛 "no PasswordEncoder mapped for the id null"。
3. **配 issuer**：`jauth-hub.issuer: http://localhost:8081`（对外可达地址；本示例与业务同进程同端口）。
4. **配 rs 内省**：`jauth-hub.rs.introspection-uri/client-id/client-secret` 三项齐备 rs-starter 才激活，
   内省调用方须为机密客户端（SPEC §4）。本示例指向**自身**的 `/oauth2/introspect` 做闭环演示；拆部署时把它
   指向独立 jauth-hub 即可，其余不变。
5. **建客户端**：`jauth-hub.clients[n]` 启动播种（幂等，不是第二真源）。无 secret = 公开客户端（强制 PKCE）；
   带 secret = 机密客户端。宿主 default 链按 `DemoSecurityConfiguration#demoDefaultSecurityFilterChain` 自配
   （denyAll + 显式白名单，jauth 链只认领协议端点，互不越界）。

**白得 UI（v1.5）**：再引 `jauth-hub-front-dist` 依赖（见 pom）并放行 `/front/**`（本示例已做），
`mvn -Pdist` 构建后 `/front/**` 即你的登录皮——嵌入契约的新形态示范（深链回退等宿主侧边界见该模块 README）。

正式嵌入生产建议切 `jauth-hub.storage: jdbc` 并提供 DataSource（Flyway 私有历史表自动迁移，不撞宿主自己的
Flyway）。memory 模式语义：PAT 禁用、审计降级内存缓冲、签名密钥重启即换（面向 demo）。

## @RequiresScope：方法级 scope 三合一（v1.2 C4）

`@RequiresScope(value = "orders:read", desc = "读取订单")`（见 `OrdersController`）一次标注获得三重接线：

1. **自动注册**：启动扫描同 JVM 全部 HandlerMethod，scope 幂等进目录（consent 页可见、PAT/安装勾选面
   不再被 A0505"目录外 scope"拒）——宿主自有接口的 scope 由此闭环进目录，零手写注册。
2. **声明式校验**：请求期拦截器校验当前令牌 authorities，scope 原串（冒号格式 = spring-plus 权限键形态）
   与 `SCOPE_` 前缀（rs-starter/Spring Security 默认映射）都认；缺失抛 Spring Security 标准
   `AccessDeniedException`（渲染 403）。rs-starter 配了自定义 `authority-prefix` 的宿主走手写规则。
3. **兜底文案**：desc 在 consent 页 i18n key（`jauth.scope.<name>`）未命中时兜底展示，否则回退裸名。

`sensitive` 属性本批只携带不消费（sudo 联动后续批次）。注解不挂即零行为，无开关。

**Boundary (EN):** annotation scanning covers only the same JVM (embedded host's own endpoints); a
separately deployed resource server must still register its scopes explicitly via `ScopeCatalog#register`.
The interceptor accepts both the raw scope string (spring-plus permission-key form) and the default
`SCOPE_` prefix; hosts using a custom `authority-prefix` on the rs-starter should write their own rule.

## 端到端手动演练（curl 走授权码 + PKCE）

前置：应用已起（8081）。PKCE 用一对固定演示值（生产请每次随机生成，浏览器端可参考 jauth-hub-app 的 /demo 教学区）：

- code_verifier：`embedded-demo-fixed-verifier-0123456789abcdef0123456789abcdef`
- code_challenge（其 SHA-256 的 base64url）：`UVdgy_sF4MDP-QsmE_-NASPVXoBm7YACk3FNlTZQmlU`

```bash
# 1. 浏览器打开授权地址（登录 user / demo-user-password-placeholder，consent 页同意授权）
#    浏览器最终跳到 http://localhost:8081/callback?code=XXX&state=demo —— 页面本身 404 无妨，从地址栏复制 code
open "http://localhost:8081/oauth2/authorize?response_type=code&client_id=embedded-demo-public&redirect_uri=http://localhost:8081/callback&scope=openid%20profile%20orders:read&state=demo&code_challenge=UVdgy_sF4MDP-QsmE_-NASPVXoBm7YACk3FNlTZQmlU&code_challenge_method=S256"

# 2. 用授权码 + verifier 换令牌（授权码一次性、5 分钟有效，code 换成上一步复制的值）
curl -s -X POST http://localhost:8081/oauth2/token \
  -d grant_type=authorization_code \
  -d client_id=embedded-demo-public \
  -d redirect_uri=http://localhost:8081/callback \
  -d code=PUT_THE_CODE_HERE \
  -d code_verifier=embedded-demo-fixed-verifier-0123456789abcdef0123456789abcdef
# → {"access_token":"…","refresh_token":"…","scope":"openid profile orders:read","token_type":"Bearer",...}

# 3.（资源服务器同款姿势）内省看看令牌背后是谁——机密客户端凭证走 Basic
curl -s -X POST http://localhost:8081/oauth2/introspect \
  -u embedded-demo-rs:embedded-demo-rs-dev-only-placeholder \
  -d token=THE_ACCESS_TOKEN
# → {"active":true,"sub":"…","username":"user","scope":"openid profile orders:read",...}

# 4. 持令牌调宿主自家受保护接口（闭环完成）
curl -s http://localhost:8081/api/orders -H "Authorization: Bearer THE_ACCESS_TOKEN"
# → {"for":"user","orders":[{"id":"order-1001","item":"jauth-hub embedded demo order"}]}

# 对照：公开接口无需令牌；受保护接口无令牌 401
curl -s http://localhost:8081/public/hello
curl -i http://localhost:8081/api/orders
```

自动化的端到端（真实登录/consent/换码全程）在 B7 补齐；本示例测试先钉住"公开 200 / 无 token 401"两态。
