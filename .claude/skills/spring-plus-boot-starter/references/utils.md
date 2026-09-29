# boot.utils 通用工具速查

包：`io.github.oatelauser.springplus.boot.utils`。ADR 0003 从 web.utils 迁入（`AssertUtils`/`JsonUtils` 留守 web）。规则：这些工具已存在，业务代码**不要再自写**同功能轮子。

## ApplicationContextHolder —— 静态取容器

```java
ApplicationContext ctx   = ApplicationContextHolder.getApplicationContext();   // 未就绪抛异常
ApplicationContext ctx2  = ApplicationContextHolder.getApplicationContextOrNull(); // 可空
boolean ready            = ApplicationContextHolder.isReady();
```

注册于 `spring.factories` 的 `ApplicationContextInitializer`，容器刷新前就位。非 Boot 环境手动 `setContext(...)`。

## ApplicationContextUtils —— 容器解剖

`getDefaultListableBeanFactory(context)`：取底层 `DefaultListableBeanFactory`（动态注册 Bean、查依赖关系时用）。

## BeanUtils —— Bean 层检索与排序

```java
List<T> sorted        = BeanUtils.sort(beanFactory, MyInterface.class);  // 容器内某接口全部实现，按 Ordered 排序
List<T> sorted2       = BeanUtils.sort(collectionOfBeans);               // 离线集合版
T bean                = BeanUtils.getBeanOfGenerics(factory, MyClass.class, ArgA.class, ArgB.class);  // 按泛型参数定位 Bean
boolean present       = BeanUtils.isPresentAnnotatedBean(registry, MyAnnotation.class);
boolean hasAlias      = BeanUtils.hasAlias(registry, "beanName", "alias");
```

## AnnotationUtils —— 注解查找（含元注解展开）

```java
T ann  = AnnotationUtils.findMethodAnnotation(annotationClass, invocation);   // 方法上找（含桥接/接口）
T ann2 = AnnotationUtils.getMethodAnnotation(annotationClass, invocation);    // 精确方法
T ann3 = AnnotationUtils.findMethodAnnotation(method, annotationClass, clazz);
T ann4 = AnnotationUtils.findMethodParameterAnnotation(...);                   // 方法参数注解
```

`find*` 系列 元注解归并展开（`@RequiresAdminRole` 上叠 `@RequiresRole` 能被找到就靠它）。

## FileResources —— classpath / 应用目录资源

```java
Resource res = FileResources.getResource("schema/index.json");   // classpath 优先
ApplicationHome home = FileResources.HOME;                        // 应用安装根目录（打包运行时定位外部配置）
```

## LogSanitizer —— 日志脱敏

```java
String safe     = LogSanitizer.maskSensitiveValues(payload);   // JSON/form 键值对掩码（***）
String safeLine = LogSanitizer.sanitizeLine(value);            // 单行清洗 + 512 字符截断（防日志伪造 CRLF）
boolean sens    = LogSanitizer.isSensitiveKey("password");
```

默认敏感键集 `DEFAULT_SENSITIVE_KEYS`：`password` / `passwd` / `token` / `secret` / `authorization` / `phone` / `mobile` / `idcard`（大小写不敏感、子串命中）。BODY 级客户端日志与请求追踪旁录已自动接入；**新增业务敏感字段（如 `bankCard`）默认键集不会命中**——要么扩展键集，要么字段名对齐默认集。

## InsecureTlsHelper（@Deprecated）

`trustAllContext()` / `trustAllSocketFactory()` / `trustAllManager()` / `allowAllVerifier()`。**仅测试联调可用，生产使用视同漏洞**；业务代码禁止直接引用——ApiClient 的 `ssl.allow-insecure` 是唯一受控入口。
