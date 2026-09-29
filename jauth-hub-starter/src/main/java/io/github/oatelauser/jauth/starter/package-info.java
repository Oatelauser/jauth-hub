/**
 * jauth-hub starter 模块：接管式自动配置（B4 落地）。
 *
 * <p>唯一接入点（SPEC §2）：宿主引入本 starter 即 jauth 装配链生效——协议安全链、存储双实现（memory 默认 /
 * jdbc 条件）、令牌三件（opaque/JWT/refresh + claims 贡献）、JWK（jdbc 轮转 / memory 短命）、响应 SPI、
 * 三页 + 教学层 + i18n/模板命名空间、client 播种、CORS；spring-plus-web 在 classpath 时桥接 SimpleResponse
 * 渲染器。嵌入契约：宿主必须提供 UserDetailsService（jdbc 模式另需 DataSource），其余一切
 * ConditionalOnMissingBean 可替换。
 */
package io.github.oatelauser.jauth.starter;
