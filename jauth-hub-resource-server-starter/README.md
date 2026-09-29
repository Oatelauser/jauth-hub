# jauth-hub-resource-server-starter

资源服务器薄封装（SPEC §2/§4）：预接线 introspection（机密客户端凭证 + 30s 正/负缓存，手写 TTL Map，无 Caffeine）与 scope→authority 映射。**只供 bean，不创建 SecurityFilterChain**；401/403 渲染归宿主标准 Spring Security 语义（06/08 票裁定）。零 spring-plus 依赖。

## 宿主接入

1）引依赖：

```xml
<dependency>
  <groupId>io.github.oatelauser</groupId>
  <artifactId>jauth-hub-resource-server-starter</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

2）yml 四项（前三项必填，缺任一 starter 整体休眠；`authority-prefix` 可选，空 = scope 原样作 authority）：

```yaml
jauth-hub:
  rs:
    introspection-uri: https://jauth.example.com/introspect
    client-id: my-resource-server        # 内省调用方须为机密客户端（06 票）
    client-secret: ENC(...)              # 生产勿明文
    authority-prefix: "SCOPE_"           # 可选；read:user -> SCOPE_read:user
```

3）宿主自己的安全链上一行接入（注入 `JauthResourceServerConfigurer`）：

```java
@Bean
SecurityFilterChain apiChain(HttpSecurity http, JauthResourceServerConfigurer jauthRs) throws Exception {
    return http.securityMatcher("/api/**")
            .authorizeHttpRequests(a -> a.anyRequest().authenticated())
            .oauth2ResourceServer(jauthRs)
            .build();
}
```

内省结果（sub/username/scope/orgs 等，07 票富化字段）照单全收进 principal attributes；`orgs` 等业务字段供细粒度鉴权自取。缓存参数（`cache.ttl`/`cache.negative-ttl`/`cache.max-entries`，默认 30s/30s/1000）按部署调整；自定义 `OpaqueTokenIntrospector` bean 可整体接管（缓存包装随之让位）。
