package io.github.oatelauser.jauth.core.client;

/**
 * {@link InMemoryClientOwnerResolver} 契约测试：登记表即语义，无库。
 *
 * @author oatelauser
 */
class InMemoryClientOwnerResolverTest extends AbstractClientOwnerResolverContractTest {

    private final InMemoryClientOwnerResolver resolver = new InMemoryClientOwnerResolver();

    @Override
    protected ClientOwnerResolver resolver() {
        return this.resolver;
    }

    @Override
    protected void seed(String registeredClientId, ClientOwner owner) {
        this.resolver.put(registeredClientId, owner);
    }
}
