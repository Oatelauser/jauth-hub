# 优雅停机与启动序

包：`io.github.oatelauser.springplus.boot.lifecycle`。

## 三个角色

| 类型 | 职责 | 触发时机 |
|---|---|---|
| `StartupProcess`（接口，方法 `void start()`） | 业务启动钩子（预热缓存、订阅就绪等） | `WebServerPostProcessor` 在 **Web 容器接受请求之前**触发容器中全部实现 |
| `ShutdownHook`（接口，extends Ordered，方法 `void shutdown()`） | 业务停机钩子 | 容器停机时按 `getOrder()` **升序**逐个执行（异常记 ERROR 不中断后续） |
| `SmartGracefulShutdownHandler`（SmartLifecycle，phase = `DEFAULT_PHASE - 1`） | 停机编排 | Web 容器自身的优雅停机 lifecycle（phase 更高）**先停**，随后本 handler 执行 hooks |

## 用法

```java
@Component
public class CacheWarmup implements StartupProcess {
    @Override
    public void start() {         // Web 容器接受请求前执行；抛异常阻断启动
        dictionaryCache.reloadAll();
    }
}

@Component
public class MqConsumerShutdown implements ShutdownHook {
    @Override
    public int getOrder() { return 100; }      // 升序：数值小先执行

    @Override
    public void shutdown() {
        consumer.pause();      // 资源关闭类 hook 排后（order 大），先做摘流量类动作
    }
}
```

## 顺序语义（以代码为准）

- **摘流量在 hooks 之前是 phase 机制保证的**：Spring 停机按 phase 降序 stop，Web 容器的 graceful lifecycle（高 phase）先停（停接新流量、等在途请求），然后才轮到 `SmartGracefulShutdownHandler`（`DEFAULT_PHASE - 1`）执行业务 hooks
- **hooks 之间是 `getOrder()` 升序**：注入的 `List<ShutdownHook>` 已按 Ordered 排序，逐个执行；单个 hook 抛异常记 `log.error` 后继续下一个（不中断停机）
- 依赖 Spring 容器销毁回调的资源（`@PreDestroy` / `DisposableBean`）在 SmartLifecycle stop 之后才销毁——hooks 里可以安全使用容器 Bean

## 红线与陷阱

- **不要**把 `ShutdownHook` 逻辑注册成 `@PostConstruct` / `Runtime.addShutdownHook`——绕过 handler 就失去逆序保证，资源先于流量关闭会产生半失败请求
- `StartupProcess` 抛异常会阻断启动（fail-fast）：可重试的预热放后台线程，不可失败的才同步抛
- 需要精细控制停机超时/宽限期时用 Spring Boot 官方 `server.shutdown: graceful` 配合本机制，不重造轮子
