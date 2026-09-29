# 异常体系（JSON / SSE / NDJSON 三协议）

包：`io.github.oatelauser.springplus.web.error`（子包 annotation / descriptor / engine / mapper / output / sse）。

## ServiceException

`ServiceException` **直接实现 `ServerStatus`**——全局体系对它原生直通翻译：抛出即按其携带的 code/msg 渲染，**无须任何注解 / Mapper**。其余异常才需要走映射（注解 / Mapper / 兜底）。

```java
throw new ServiceException(BusinessStatus.DATA_NOT_EXIST);
throw new ServiceException(BusinessStatus.DATA_NOT_EXIST, cause);     // 带因果链
throw new ServiceException("B0001", "签名校验失败");                    // 临时码
throw new ServiceException("B0001", "签名校验失败", cause);
throw new ServiceException("只给 message 的重载");                      // 码走默认值

throw new ServiceException(BusinessStatus.DATA_NOT_EXIST).withStack();  // 默认 WARN 不打堆栈，链式强制打
throw new ServiceException(BusinessStatus.DATA_NOT_EXIST).signal();     // 信号异常：fillInStackTrace 都省（高频路径零成本）
throw new ServiceException(BusinessStatus.DATA_NOT_EXIST)
        .status(HttpStatus.CONFLICT);                                    // 链式指定真实 HTTP 状态码
```

业务自定义异常类想获得同等直通待遇：实现 `ServerStatus`（或 `ServerStatusProvider`），抛出即渲染，无须注解。

## 声明式映射注解（P0，最高优先级）

```java
@JsonExceptionResponse(value = RateLimitException.class, code = "A0429",
        msg = "请求过于频繁", httpStatus = HttpStatus.TOO_MANY_REQUESTS,
        logPolicy = LogStackPolicy.NEVER)
```

- 三协议派生注解共享同一套属性：`@JsonExceptionResponse` / `@SseExceptionResponse` / `@NdjsonExceptionResponse`；`@ExceptionResponse` 为元注解
- 贴的位置：**异常类上**（推荐，全端点生效）或 Controller 方法/类上（局部覆盖）
- `msg` 支持占位符：`{exception}`（异常消息）、`{exceptionClass}`（simpleName）
- `httpStatus` 输出**真实** HTTP 状态码（不是"业务码 4xx + HTTP 200"的老模式）
- `output = XxxOutput.class` 指定 `ExceptionBodyCustomizer` 实现类替换错误体（必须是 Spring Bean；返回 null 回落默认结构；微信 errcode/errmsg 风格示例见 examples 的 `WechatStyleOutput`）
- `logPolicy = LogStackPolicy.ALWAYS / NEVER` 覆盖默认日志策略
- 同一异常贴多协议注解时各自作为协议过滤器工作（JSON 接口命中 JSON 注解，SSE 接口命中 SSE 注解），启动期 warn 提示

## 解析优先级（两级维度，别混）

**descriptor 解析优先级**（同一异常怎么翻译）：

1. **P0** 方法/类级注解
2. **P1** 全局 handler 自建的 defaultDescriptor（如 `@ExceptionHandler(SQLException)` 内部构造）
3. **P2** `ExceptionMapper` 链
4. **P3** 兜底（`spring-plus.web.error-response.*` 配置的 code/msg）

**advice 咨询顺序**（异常先到哪个 @RestControllerAdvice）：

- 模块/业务 advice（显式 `@Order`，段位约定 0~900）> 全局 `GlobalExceptionAdvice`（`LOWEST_PRECEDENCE` 兜底）具体 handler > 其 `Exception.class` 兜底
- 跨 advice 先到先得；两类契约违例没有启动期校验、后果静默：非全局 advice 声明 `Exception`/`Throwable` 级兜底（遮蔽全局全部具体 handler）、无显式 `@Order`（与兜底平局，先后由注册顺序决定）——务必自查

## ExceptionMapper（P2 扩展点）

接口**非泛型**：`map(Throwable, ExceptionMapperContext)`，不认识当前节点就返回 null（异常因果链遍历由 `ExceptionMapperChain` 负责）；`order()` 决定链内优先级。

```java
// 路线 1：独立 Mapper Bean（自动装配进链，返回值需 .build()）
@Component
public class RateLimitExceptionMapper implements ExceptionMapper {
    @Override
    public int order() { return 100; }

    @Override
    @Nullable
    public ErrorDescriptor map(Throwable ex, ExceptionMapperContext ctx) {
        if (ex instanceof RateLimitException rle) {
            return ErrorDescriptor.of("A0429", "请求过于频繁", ex)
                    .details(Map.of("retryAfterSeconds", rle.getRetryAfterSeconds()))
                    .build();
        }
        return null;   // 不是自己的异常，放行给链上后续 Mapper
    }
}
```

另一种等价路径：异常类自身实现 `ExceptionMapper`（无须独立 Bean，examples 的 `RateLimit2Exception` 为对照样例）。

业务接入优先级：异常类上贴注解 > 独立 Mapper Bean > Controller 方法注解（最后手段，散落难维护）。

## 模块级 advice（多 advice 共存，1.1.0+）

模块/业务想接管自己的异常域。范本即 security 模块的 `SecurityExceptionAdvice`（`@Order(100)`）：

```java
@RestControllerAdvice
@Order(100)   // 必须显式；0~900 段位约定，数值小于全局兜底即优先
public class OrderExceptionAdvice {

    @ExceptionHandler(OrderClosedException.class)
    public Object handle(OrderClosedException ex, HttpServletRequest request,
            HttpServletResponse response) {
        ErrorDescriptor descriptor = ErrorDescriptor.of("B1001", "订单已关闭", ex)
                .statusIntent(HttpStatus.CONFLICT)
                .build();
        return engine.dispatch(ex, descriptor, request, response);   // 引擎统一渲染三协议
    }
}
```

- 要渲染就构造 `ErrorDescriptor` 交给 `ExceptionOutputEngine.dispatch(ex, descriptor, request, response)`，不要自己写响应体（三协议 / SSE app-error / NDJSON 补行由引擎统一处理）
- 唯一合法的 rethrow 例外：语义需要透传原异常（如 `AccessDeniedException` 交还 Security 的 `ExceptionTranslationFilter`）

## 配置键（GlobalExceptionProperties）

| 键 | 默认 | 说明 |
|---|---|---|
| `spring-plus.web.error-response.code` | 系统错误码 | 兜底 code |
| `spring-plus.web.error-response.msg` | 系统错误文案 | 兜底 msg |
| `spring-plus.web.error-response.show-error` | `false` | true 时错误 message 透出 `ex.getLocalizedMessage()`（含内部信息，生产禁开） |
| `spring-plus.web.error-response.log.print-stack-for-unknown` | `true` | 未知异常打堆栈 |
| `spring-plus.web.error-response.log.protocol-tag` | `true` | 日志带协议标签 |
| `spring-plus.web.error-response.sse.default-event-name` | `app-error` | SSE 错误事件名 |

## 已知陷阱

- 校验类异常的输出形态：`@Valid @RequestBody` 失败 → `A0430` + `details.violations`（Spring 7 fail-fast，**单条**）；非 body 参数约束失败（`HandlerMethodValidationException`）→ 同构 `A0430` 但聚合**全部**参数结果
- `output` 指向的类必须注册为 Spring Bean，启动期 `getBean` fail-fast
- NDJSON 流内异常时响应已提交：`httpStatus` 无效（不可改），错误以**追加一行**呈现
