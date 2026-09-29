/**
 * jauth-hub resource-server starter（薄封装，SPEC §2/§4）：预接线内省（机密客户端凭证 + 30s 正/负缓存）与
 * scope→authority 映射。只供 bean，不建 SecurityFilterChain；401/403 渲染归宿主标准语义。零 spring-plus 依赖。
 */
package io.github.oatelauser.jauth.resourceserver;
