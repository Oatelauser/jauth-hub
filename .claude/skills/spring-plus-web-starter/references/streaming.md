# 流式响应（SSE / NDJSON / Chunk / 文件下载）与请求追踪

包：`io.github.oatelauser.springplus.web.stream` 与 `web.trace`、`web.error.sse`。

## HttpWriterFactory（编程式四件套入口）

自动配置注入，一行取得"协议头已配好"的写入器：

```java
private final HttpWriterFactory streamWriterFactory;

SseStreamWriter     w1 = streamWriterFactory.sse(response);
NdjsonStreamWriter  w2 = streamWriterFactory.ndjson(response);
ChunkStreamWriter   w3 = streamWriterFactory.chunk(response);
FileDownloadWriter  w4 = streamWriterFactory.download(response, "租户导出报表.csv");   // 中文文件名 RFC 5987
FileDownloadWriter  w5 = streamWriterFactory.download(response, "预览.pdf", true);    // inline=true 浏览器内预览
```

写入器直写 servlet 输出流并逐块 flush；不设 Content-Length，容器自动 chunked 传输。

### NDJSON（LLM token 流 / 日志导出）

```java
@GetMapping(value = "/stream", produces = MediaType.APPLICATION_NDJSON_VALUE)
public void stream(HttpServletResponse response) throws Exception {
    NdjsonStreamWriter writer = streamWriterFactory.ndjson(response);
    for (int i = 0; i < 10; i++) {
        writer.writeLine(Map.of("seq", i, "token", "delta-" + i));   // 每行一个完整 JSON
    }
    writer.close();
}
```

要点：

- `produces = "application/x-ndjson"` 同时声明协议 + 让异常时 `OutputProtocolResolver` 探测为 NDJSON
- `NdjsonStreamWriter` 链式 API：`writeLine(Object)`（序列化为一行 JSON）/ `writeRawLine(String)`（原样写）/ `writeErrorLine(code, msg)`（**手动**补一行错误 JSON——正常情况用不到，异常冒泡就是自动补写）
- **流内异常零样板**：直接抛 `ServiceException` 冒泡到全局处理器，`NdjsonExceptionProcessor` 检测响应已提交后在既有流上**追加一行**错误 JSON（状态码与头已发出不可改，也不需要改）
- 补写错误行的 body 可用 `@NdjsonExceptionResponse(output = XxxOutput.class)` 定制（须为 Spring Bean，返回 null 回落默认行）

### Chunk（按块文本）

```java
response.setContentType("text/plain;charset=UTF-8");   // chunk() 不代设 Content-Type（载体泛化）
ChunkStreamWriter writer = streamWriterFactory.chunk(response);
writer.writeChunk("chunk #0\n");
writer.close();
```

### 文件下载

```java
FileDownloadWriter writer = streamWriterFactory.download(response, "租户导出报表.csv");
writer.write(csv.getBytes(StandardCharsets.UTF_8));             // byte[] 重载
writer.write(new ByteArrayInputStream(bytes));                  // InputStream 重载；Content-Type 按扩展名推断
writer.finish();   // 不是 close()：下载语义是 finish（收尾并关闭底层流）
```

## SSE 三档（由低到高）

**A 档（裸 emitter，自己管一切）**：业务自有 `SseEmitter`，异常自己 try/catch。仅当确有特殊定制时用。

**B 档（自有 emitter + 框架转错误事件）**：业务自己的 emitter/executor，只把异常交给框架：

```java
SseEmitter emitter = new SseEmitter(0L);
executor.execute(() -> {
    try {
        emitter.send(SseEmitter.event().name("tick").data("frame"));
        throw new ServiceException(BusinessStatus.DATA_NOT_EXIST);
    } catch (Throwable ex) {
        sseExceptionEmitter.completeWithError(emitter, ex, request);   // 转 app-error 事件并 complete
    }
});
return emitter;
```

**C 档（连接封装，零样板，推荐）**：emitter + executor + 自动错误转换全托管：

```java
@GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter sse(HttpServletRequest request) {
    SseConnection conn = sseConnectionFactory.open(request, demoExecutor);
    conn.execute(emitter -> {
        emitter.send(SseEmitter.event().name("tick").data("frame#1"));
        throw new ServiceException(BusinessStatus.DATA_NOT_EXIST);   // 任意异常自动转 app-error，无须 try/catch
    });
    return conn.emitter();
}
```

SSE 接口的异常处理与 JSON 同构：方法/异常类上贴 `@SseExceptionResponse` 即可，握手期 `@Valid` 失败也会输出 `event: app-error`（而非 JSON 404）。

## 请求追踪 @RecordHttp

`@RecordHttp`（标记注解，可贴方法/类/元注解）+ 启动类 `@EnableRecordHttp` 开启 `LoggingHttpTraceFilter`：

- 自动记录请求/响应摘要；响应侧**有界旁录**（直写透传 + 超限占位 + 二进制/multipart 跳过）——大响应不再整包缓冲
- BODY 级日志自动掩码敏感键（boot 模块 `LogSanitizer` 默认键集）；非默认命中的自定义业务敏感字段仍会落日志，新增敏感字段时评估扩展键集

## 陷阱

- NDJSON/Chunk 写入器是**同步阻塞写**（虚拟线程场景直接用）；要异步用 SSE 的 executor 模式
- SSE 错误事件名默认 `app-error`，可经 `spring-plus.web.error-response.sse.default-event-name` 改
- `FileDownloadWriter` 结束用 `finish()`；其他写入器用 `close()`
