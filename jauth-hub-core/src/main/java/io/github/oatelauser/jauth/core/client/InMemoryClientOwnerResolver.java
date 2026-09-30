package io.github.oatelauser.jauth.core.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.util.Assert;

/**
 * {@link ClientOwnerResolver} 的内存实现：memory 存储模式下 owner 的进程内登记表，重启即失。
 *
 * <p>种子客户端不携带归属（ClientSeedProperties 无 owner 面），登记走 {@link #put}——测试与宿主装配
 * 以此在 memory 模式表达组织客户端（平台/个人语义即"未登记"）。
 *
 * @author oatelauser
 */
public class InMemoryClientOwnerResolver implements ClientOwnerResolver {

    private final Map<String, ClientOwner> ownersByRegisteredClientId = new ConcurrentHashMap<>();

    @Override
    public @Nullable ClientOwner findOwner(String registeredClientId) {
        Assert.hasText(registeredClientId, "registeredClientId cannot be empty");
        return this.ownersByRegisteredClientId.get(registeredClientId);
    }

    /**
     * 登记客户端归属（同 id 重复 put 即覆盖）。
     *
     * @param registeredClientId oauth2_registered_client.id
     * @param owner 归属
     */
    public void put(String registeredClientId, ClientOwner owner) {
        Assert.hasText(registeredClientId, "registeredClientId cannot be empty");
        Assert.notNull(owner, "owner cannot be null");
        this.ownersByRegisteredClientId.put(registeredClientId, owner);
    }
}
