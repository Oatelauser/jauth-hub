# 09 统一响应对象抽象（通用性关键设计）

Type: grilling
Status: resolved

## Question

用户需求：jauth-hub 返回给前端的异常/响应要有统一对象，但**项目要通用，每个宿主系统的统一响应结构不一样**（R\<T\>、SimpleResponse、Result\<T\>…），库不能写死自己的结构。

决定：

- **端点二分法**（关键前提）：OAuth2/OIDC **协议端点**（/authorize、/token、/introspect、/revoke、userinfo…）的错误格式是 RFC 标准（`error`/`error_description`），**不允许**自定义成业务响应对象；只有**非协议端点**（/me、selfservice 管理接口、demo 区、登录/consent 页的交互接口）才走统一响应
- SPI 形态：抽象一个最小渲染契约（如 `ResponseRenderer`：success(data)/fail(code,message) 两个方法？），core 提供朴素默认实现（裸 `{code,message,data}`），宿主注册自己的实现即映射到自家结构
- 错误码体系：jauth-hub 自己的 code 空间怎么划（借鉴 spring-plus 的 00000/A0/B0/C0 分段还是独立定义）
- 与 spring-plus-web-starter `SimpleResponse` 的衔接留给票 08（作为现成实现候选）
- selfservice 页面（Thymeleaf）走 JSON API 还是混合——响应 SPI 只管 JSON 面

产出：SPI 接口定义草案（方法签名级）+ 端点二分清单 + 错误码分段决议。

## Answer

四问全按推荐裁定（用户确认；Q1 的装配形式澄清为标准 Bean 条件装配）。

**SPI 定义（签名级草案）**

```java
public interface ResponseRenderer {
    Object renderSuccess(Object data);              // 成功包装，返回宿主自己的响应类型
    Object renderFail(String code, String message); // 失败包装
}
```

- 装配形式：starter 注册默认实现并标 `@ConditionalOnMissingBean(ResponseRenderer.class)`；宿主声明自己的 `@Bean` 即整体替换——与 03 决议 4"一切可替换"同机制，无 ServiceLoader
- 内部异常流：jauth-hub 自有 `@RestControllerAdvice` 接住 `JauthException(code,message)` → 委托 SPI `renderFail`；不依赖宿主全局 advice（防其误包装协议端点）
- 分页不进 SPI：分页作为 data 携带通用分页 DTO，字段名**照抄家族 `PageResponse` 线上契约**（`item`（单数）/`total`/`pageNum`/`pageSize`/`totalPage`——1.0.0 已发布即契约，复审修正原 items/page/size 写法）

**端点三分清单**

| 端点类 | 响应形态 | 依据 |
|---|---|---|
| OAuth2/OIDC 协议端点（/authorize /token /introspect /revoke /userinfo /device…） | RFC 标准 `error`/`error_description`，**禁止包装** | 标准合规 |
| 平台 API（/me） | 裸用户 JSON，**不包装** | 对齐 userinfo/GitHub /user |
| 管理/自助/demo 接口（selfservice、app 管理页、/demo） | SPI 统一响应 | 本票管辖范围 |

**错误码分段**：沿用 spring-plus 家族分段惯例（00000 唯一成功 / A0 客户端 / B0 业务 / C0 系统），jauth-hub 正式占段 **`A05xx`（授权/客户端请求错）+ `B05xx`（认证中心内部错）**。（复审修正：原定 A03xx/B03xx 与 spring-plus-security 已占用的 `A0301` 及家族 B0 通用段撞车，用户以家族码表立法者身份确认改段；具体码表实施期落。）

**默认实现字段名**：照抄 `SimpleResponse`（`code/message/data`）——独立模式切真 SimpleResponse 类型时前端 JSON 零变化；与 spring-plus-web 的正式衔接（app 模块直接用 SimpleResponse 做默认 renderer）归票 08。
