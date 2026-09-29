# 参数校验注解

包：`io.github.oatelauser.springplus.web.validation`（field / collection / clazz 三层）。

## 字段级（field）

```java
public class CreateUserCmd {

    @NotBlank                                       // 必填叠加：@Phone 空值默认通过！
    @Phone
    private String phone;

    @EnumValue(enumClass = UserStatus.class)        // 值必须是枚举成员；method 默认 "name"（可用 "getCode" 等）
    private String status;

    @ListValues({"male", "female"})                 // 字面量白名单
    private String gender;
}
```

- `@Phone` / `@EnumValue` / `@ListValues` 空值（null/空串）一律**默认通过**——它们只管"有值时值必须合法"；必填约束叠加 `@NotBlank` / `@NotNull`
- `@EnumValue` 属性：`enumClass`（目标枚举类）、`method`（取值方法名，默认 `name`）

## 集合元素级（collection）

```java
@NoNullElement        // 元素不可为 null
@UniqueElement        // 元素不可重复
private List<Long> tenantIds;
```

与容器自身的 `@NotEmpty`（集合非空）区分：这两个约束的是**元素**。

## 类级（clazz）：@ClassValidator

**跨字段/一致性校验写在这里，不写在 Controller 的 if 里**。`@ClassValidator`（可 `@Repeatable`）裸贴在类上（无 `value` 属性），校验逻辑由类自身提供——默认走 `Validatable.validate()`：

```java
@Data
@ClassValidator                                    // 裸注解，不带任何参数
public class IndicationPushRequest implements Validatable {

    @NotNull
    private LocalDate startDate;

    @NotNull
    private LocalDate endDate;

    @Override
    public ValidationResult validate() {
        // 字段必须先判 null（见下方陷阱），条件不成立时什么都不加即成功
        return ValidationResult.builder()
                .addFieldErrorIf(startDate != null && endDate != null && startDate.isAfter(endDate),
                        "startDate", "startDate 不能晚于 endDate")
                .build();
    }
}
```

Controller 只保留 `@Valid @RequestBody`，不出现 `if (request.getStartDate().isAfter(...)) return fail(...)`——错误走统一 violations 输出。

### ValidationResult 写法速查

- `builder().addFieldErrorIf(cond, field, message)`：条件字段错误（最常用）
- `builder().addFieldErrorIf(cond, field, message, Map.of("min", min, "max", max))`：消息模板 `{min}` 参数化
- `builder().addErrorIf(cond, message)`：条件类级错误（不绑定字段）
- `builder().addFieldError(field, message)` / `addError(message)`：无条件版
- 静态族：`success()` / `failure(message)` / `failure(field, message)` / `failure(errors)`
- `build()` 时无错误自动等价 `success()`

### 不实现 Validatable 的写法

任意无参方法命名 `validate()`、返回 `ValidationResult` 即可被识别；方法名不同时 `@ClassValidator(method = "checkRange")` 指定。找不到校验方法默认通过（debug 日志）。

### 注解属性

- `method`：校验方法名（默认 `validate()` 或 `Validatable` 接口）
- `failFast`：注解级首错即停（默认 false，只影响本注解结果的错误条数）
- `policy`：与字段约束的执行顺序——`AFTER_FIELD`（默认）/ `BEFORE_FIELD` / `PARALLEL`
- `order`：多个类级校验器间的优先级，越小越先
- 可重复标注（`@Repeatable`）挂多组校验逻辑

全局开关 `spring-plus.web.validation.fail-fast` 默认 `true`：全应用 `@Valid` 校验首错即停（Spring 7 下 `@Valid @RequestBody` 本身也是 fail-fast）。

## 编程式校验：ValidationUtils

不经过 `@Valid` 注解、在代码里手动触发校验时用（`web.validation` 包）：

```java
BindingResult result = ValidationUtils.validate(cmd);                    // 全量
BindingResult result = ValidationUtils.validate(cmd, "phone");           // 单字段
Validator validator = ValidationUtils.obtainValidator();                 // 取底层 Validator 自由组合
ExecutableValidator ev = ValidationUtils.obtainExecutableValidator();    // 方法参数级校验
```

返回标准 `BindingResult`，可自行决定抛 `ServiceException` 还是聚合进响应。

## 校验失败的输出形态（不用自己处理）

- `@Valid @RequestBody` 失败 → `A0430` + `details.violations`（**Spring 7 是 fail-fast，只有单条**；多字段并发失败时字段顺序不定，验收测试须用单失败字段保证确定性）
- 非 body 参数约束（`@RequestParam @Min(1)` 等方法级校验）→ `HandlerMethodValidationException` 被翻译为同构 `A0430`，聚合**全部**参数结果
- SSE 接口握手期失败 → `app-error` 事件（不是 JSON）

## 陷阱

- **`validate()` 里必须自己判 null**：字段 `@NotNull` 失败并不能保证 `validate()` 不执行。默认 `fail-fast=true` 时字段失败会短路、`validate()` 不运行（实测 HV 9.1.0.Final）；但应用配置 `fail-fast=false` 后，字段失败时 `validate()` 仍被调用——null 字段直接 `startDate.isAfter(endDate)` 会 NPE，被 `ClassCheckValidator` 吞掉后返回 `Validation execution error: Cannot invoke "...isAfter"...` 这种泄漏内部签名的违规消息。写法：`startDate != null && endDate != null && startDate.isAfter(endDate)`
- `ClassValidatorPostProcessor` 反射 hibernate-validator 内部 API，HV 版本由根 POM 锁定 9.1.0.Final；HV 大版本升级（9.x → 10.x）可能破坏该集成，升级须回归 validation 域测试
- 别用 `@Validated` 包裹 + 分组校验的旧范式去模拟类级校验——`@ClassValidator` 就是为此而生
