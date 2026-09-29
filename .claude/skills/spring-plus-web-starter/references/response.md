# 统一响应、分页与状态码

包：`io.github.oatelauser.springplus.web.response`。

## SimpleResponse 工厂

```java
SimpleResponse.ok()                                  // 无数据
SimpleResponse.ok(data)                              // 带数据
SimpleResponse.ok(message, data)                     // 自定义成功文案

SimpleResponse.fail(BusinessStatus.DATA_NOT_EXIST)                    // 枚举直用
SimpleResponse.fail(BusinessStatus.PARAMETER_MISSING, "userId")      // 占位符 {0} 格式化
SimpleResponse.fail("自定义消息", BusinessStatus.DATA_NOT_EXIST)        // 覆盖文案（message 在前）
SimpleResponse.fail("B0001", "签名校验失败")                            // 自定义码
SimpleResponse.fail("B0001", "签名校验失败", Map.of("field", "sign"))   // 自定义码 + details
SimpleResponse.fail(exception)                                        // 任何实现 ServerStatusProvider 的对象（如 ServiceException）
```

失败时 `data` 恒为 null；`details` 携带结构化补充信息（`@Valid` 字段错误清单等）。

**JSON 结构（字段名与顺序即线上契约，禁改）**：

```json
// 成功
{ "code": "00000", "message": "操作成功", "data": { }, "success": true }

// 失败（@Valid 失败时 details.violations 为错误清单）
{ "code": "A0430", "message": "[age] age 不能为空", "data": null,
  "details": { "violations": [ { "field": "age", "msg": "age 不能为空" } ] }, "success": false }
```

`getSuccess()` 只认 `00000`——单一成功码，无"CREATED 二档"歧义。

## 分页四件套

```java
// 入参：继承 BasePageRequest（自带 pageNum 默认 1、pageSize 默认 10 且上限 500 的校验）
public class UserPageRequest extends BasePageRequest {
    private String keyword;   // 业务筛选字段
}

// 返回：total 在前、records 在后（以源码签名为准）
PageResponse.ok(request, total, records)
PageResponse.ok(pageNum, pageSize, total, records)   // 无 request 对象时
```

`Page` 字段（**实际 JSON 键，以源码为准**）：`item`（单数，项目约定）/ `total` / `pageNum` / `pageSize` / `totalPage`。

> ⚠️ `Page` 的 javadoc 与 README 曾把总数字段写作 `totalCount`——那是文档笔误；字段是 `total`，无 `@JsonProperty` 改名，线上 JSON 键就是 `total`（1.0.0 已发布，此即契约）。

## 状态码体系

| 段 | 含义 | 枚举 | 常见示例 |
|---|---|---|---|
| `00000` | 唯一成功码 | `SystemStatus.SUCCESS` | - |
| `A0xxx` | 客户端域 | `ClientStatus` | USERNAME_ALREADY_EXISTS、PHONE_FORMAT_ERROR、ACCOUNT_FROZEN |
| `B0xxx` | 业务域 | `BusinessStatus` | DATA_NOT_EXIST、DATA_ALREADY_EXISTS、REPEAT_SUBMIT、TIME_RANGE_ERROR |
| `C0xxx` | 系统域 | `SystemStatus` | INTERNAL_ERROR、DATABASE_ERROR、THIRD_PARTY_ERROR、SYSTEM_RATE_LIMIT |

`CommonStatus` 为跨域聚合。查码优先看枚举常量名（语义命名，可直接搜索）。

## 业务自定义状态码

业务项目的错误码定义在业务侧，两条路：

```java
// 路线 1（推荐）：业务项目内定义枚举实现 ServerStatus
public enum OrderStatus implements ServerStatus {
    ORDER_ALREADY_CLOSED("B1001", "订单已关闭，不可支付");
    private final String code;
    private final String msg;
    // 构造器 + getCode()/getMsg() 覆写
}

// 路线 2：一次性临时码
AssertUtils.isTrue(stock > 0, ServerStatus.of("B1002", "库存不足"));
```

**不要**把业务私有码加回框架模块；`AssertUtils`/`ServiceException` 的 `String code` 重载用于临时码，存量语义码应沉淀为枚举。

## ServerStatusProvider（对象自带状态码）

```java
public interface ServerStatusProvider {
    ServerStatus getServerStatus();
}
```

任何异常/结果对象实现它即可被 `SimpleResponse.fail(provider)` 直取状态码；`ServiceException` 直接实现 `ServerStatus`（更进一步），全局异常体系对它有原生直通翻译——**抛 ServiceException 不需要任何注解/Mapper**（见 exception-handling.md）。

## FieldErrorInfo

`@Valid` 失败时全局处理器把每条约束违规转成 `FieldErrorInfo`（field + msg），装进 `details.violations`。业务代码一般不直接构造它；自定义 `ExceptionBodyCustomizer` 渲染时可复用该结构（见 exception-handling.md）。
