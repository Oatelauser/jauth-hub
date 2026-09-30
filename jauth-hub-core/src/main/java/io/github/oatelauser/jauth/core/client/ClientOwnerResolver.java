package io.github.oatelauser.jauth.core.client;

import org.jspecify.annotations.Nullable;

/**
 * 客户端归属解析 SPI（B9）：授权服务 ceiling 剪枝与 consent 页 org 上下文共用的读取面——按注册客户端<b>主键
 * id</b>（非对外 client_id）取 {@link ClientOwner}。
 *
 * <p>双实现按 {@code jauth-hub.storage} 注册：jdbc 委托 {@link JauthJdbcRegisteredClientRepository#findOwnerById}；
 * memory 为进程内登记表（{@link InMemoryClientOwnerResolver#put} 暴露给装配/测试，memory 模式自此能表达组织客户端）。
 * 返回 null 或两列皆空（{@link ClientOwner#platform()}）均表示平台内置/未登记，调用方按"不剪"处理。
 *
 * @author oatelauser
 */
public interface ClientOwnerResolver {

    /**
     * 查客户端归属。
     *
     * @param registeredClientId oauth2_registered_client.id（非对外 client_id）
     * @return 归属；客户端不存在或未登记返回 null
     */
    @Nullable
    ClientOwner findOwner(String registeredClientId);
}
