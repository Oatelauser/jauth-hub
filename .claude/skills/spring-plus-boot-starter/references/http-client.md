# ApiClient 声明式 HTTP 客户端

包：`io.github.oatelauser.springplus.boot.client`（core / interceptor / adapt / metrics 子包）。

## 装配模型

**单一下游**：配置即得默认 Bean（`@ConditionalOnMissingBean`）：

```yaml
spring-plus:
  client:
    base-url: https://example.api     # 不配则不装配默认 ApiClient
```

**多下游**：一律 builder 自建（自动配置只管默认那个）：

```java
@Bean
public ApiClient githubClient(ClientHttpRequestFactoryProvider factoryProvider) {
    return ApiClient.builder()
            .properties(ApiClientSettings.of("https://api.github.com"))
            .clientHttpRequestFactoryProvider(factoryProvider)
            .build();
}
```

静态工厂还有 `ApiClient.create(baseUrl)` / `create(RestClient)` / `create(ApiClientSettings)`。

## 发请求：ApiRequest + ApiResponse

```java
// Builder 构造请求（get/post/put/patch/delete/head/method 静态入口）
ApiResponse<UserDTO> resp = apiClient.execute(
        ApiRequest.get("/users/{id}").build(), UserDTO.class);

// 便捷直调
apiClient.get("/users/1", UserDTO.class);
apiClient.post("/users", createCmd, UserDTO.class);

// 流式响应体
ApiResponse<StreamingBody> streamResp = apiClient.stream(ApiRequest.get("/big-file").build());
```

`ApiResponse<T>` 判定与取值（别手比状态码）：

```java
resp.isSuccessful();        // 2xx
resp.isClientError();       // 4xx
resp.hasException();        // IO 层异常（连接失败等）
resp.bodyOptional();        // Optional<T>
resp.requireBody();         // 直接取，无 body 抛异常
resp.map(Function, fallback);
resp.getHeader("X-Request-Id");
```

`ParameterizedTypeReference<T>` 重载支持泛型 body。

## 扩展点

- **AuthProvider**：认证注入（token 头、签名等），实现后注册 Bean，拦截器自动应用——不要每个请求手写 header
- **ClientFilter / FilterChain**：自定义过滤器挂进链（框架内置 `RetryFilter` 重试、`GzipCompressInterceptor` GZIP 压缩、`LoggingInterceptor` 日志、`SsrfGuardFilter` SSRF 校验）
- **ClientHttpRequestFactoryProvider**：引擎适配（httpclient5 为 provided 可选引擎，缺席自动降级 JDK 实现）

## 指标

classpath 有 `MeterRegistry` 时，**所有** ApiClient（含业务自建 Bean）自动注入 `api.client.requests` 指标（`ApiClientMetricsPostProcessor` 后置处理），无须业务接线。

## 配置键（spring-plus.client.*）

| 键 | 默认 | 说明 |
|---|---|---|
| `base-url` | - | 默认客户端基地址；不配不装配 |
| `connect-timeout` | `10s` | 连接超时 |
| `read-timeout` | `30s` | 读超时 |
| `follow-redirect` | `true` | 跟随重定向 |
| `strip-credentials-on-cross-host-redirect` | `true` | 跨主机重定向自动剥离 Authorization/Cookie（仅 HTTP_COMPONENTS 引擎） |
| `compression.enabled` | `false` | 请求 GZIP 压缩 |
| `ssl.verify` | `true` | 证书校验 |
| `ssl.allow-insecure` | `false` | 信任所有证书（仅内网调试） |
| `ssl.bundle` | - | SSL bundle 名（Boot 官方 bundle 机制） |
| `proxy.host` / `proxy.port` | - | 正向代理 |
| `proxy.username` / `proxy.password` | - | 代理认证 |
| `logging.enabled` | `false` | BODY 级客户端日志（自动掩码敏感键） |
| `logging.max-body-log-size` | `4096` | 日志 body 上限 |
| `logging.sensitive-headers` | `false` | 敏感头处理开关 |
| `apache-hc5.max-connections` | `100` | httpclient5 连接池总连接 |
| `apache-hc5.max-connections-per-route` | `20` | 单路由连接上限 |
| `apache-hc5.time-to-live` | `5m` | 连接存活时间 |
| `jdk.executor-threads` | `0` | JDK 引擎执行线程数（0=默认） |

## SSRF 防护（1.1.0+）

外部输入参与构造 `uri` 的调用（回调、Webhook、抓取）必须开启：

```yaml
spring-plus:
  client:
    ssrf:
      enabled: true                     # 默认 false（兼容存量）
      allowed-hosts: [api.example.com]  # 后缀匹配；为空仅私网拦截
      deny-private-network: true        # 环回/私网/链路本地/保留地址；解析失败 fail-closed
```

- 校验挂在过滤器链首：绝对 URI 覆盖 baseUrl 的注入路径、相对路径（校验 baseUrl 主机）都覆盖
- 已知局限：DNS rebinding 未做 IP pinning（浅防护）；建议启用 SSRF 的部署用 HTTP_COMPONENTS 引擎（跨主机重定向凭据剥离仅该引擎支持）

## 陷阱

- 自建多客户端时 `ApiClientSettings` 用 `of(baseUrl)` 或 `@ConfigurationProperties` 绑定第二前缀，不要复用默认前缀单例配置
- BODY 日志（`logging.enabled`）与请求追踪旁录都会掩码 `LogSanitizer` 默认键集；自定义业务敏感字段需扩展键集（见 utils.md）
