# JsonUtils 与 AssertUtils

包：`io.github.oatelauser.springplus.web.utils`。

## AssertUtils（运行时业务断言）

失败抛 `ServiceException`：**6 个方法族、32 个重载**。

| 断言 | 语义 |
|---|---|
| `notNull(obj, ...)` | 非空 |
| `isTrue(expr, ...)` | 布尔成立（业务条件） |
| `state(expr, ...)` | 状态成立（对象状态类判断） |
| `hasText(text, ...)` | 字符串有内容 |
| `notEmpty(collection/array/map, ...)` | 容器非空（三类各一） |
| `noNullElements(collection, ...)` | 元素均非 null |

四种重载（以 notNull 为例）：

```java
AssertUtils.notNull(user, BusinessStatus.DATA_NOT_EXIST);                      // 枚举码 + 默认文案
AssertUtils.notNull(user, BusinessStatus.PARAMETER_MISSING, "userId");        // 枚举码 + 占位符格式化（{0} 风格）
AssertUtils.notNull(user, BusinessStatus.DATA_NOT_EXIST, "自定义消息");         // 枚举码 + 覆盖文案
AssertUtils.notNull(user, "B0102", "金额必须为正数");                           // 临时码直抛
```

规则：条件判断 + 抛业务异常的样板**一律**用 AssertUtils 收敛，不写 if + throw；临时码只用于一次性场景，语义稳定的码沉淀为业务枚举。

## JsonUtils（唯一 JSON 门面）

```java
JsonUtils.writeValueAsString(obj);                 // 序列化
JsonUtils.writeValueAsBytes(obj);
JsonUtils.readValue(json, UserVO.class);           // 反序列化
JsonUtils.readValue(json, new TypeRef<List<UserVO>>() {});
JsonUtils.convertValue(obj, UserVO.class);         // POJO/Map 互转（走中间树）
JsonUtils.shared();                                // 取底层 JsonMapper（容器实例同源）
```

- 容器内有 `JsonMapper` Bean 时与其同源（含时间格式 / long-to-string 等定制）；非 Boot 环境（无自动配置）回落自建实例，行为与容器实例一致
- 业务代码**禁止** `new JsonMapper()` / `new ObjectMapper()`——绕过门面会丢掉全局定制

## spring.jackson.* 配置键

| 键 | 默认 | 说明 |
|---|---|---|
| `spring.jackson.time-zone` | JVM 默认 | 时区（Boot 官方键） |
| `spring.jackson.datetime-format` ⚑ | `yyyy-MM-dd HH:mm:ss` | LocalDateTime 格式（**框架扩展键**，官方文档查不到） |
| `spring.jackson.date-format` | `yyyy-MM-dd` | Date / LocalDate 格式（官方键，同名同义） |
| `spring.jackson.time-format` ⚑ | `HH:mm:ss` | LocalTime 格式（**框架扩展键**） |
| `spring.jackson.long-to-string` ⚑ | `false` | Long→String 序列化（**框架扩展键**，防 JS 精度丢失；前端直连场景开启） |

⚑ 标记键是框架在容器 `JsonMapper` 上额外下发的定制，`JsonUtils` 与 MVC 序列化均生效。
