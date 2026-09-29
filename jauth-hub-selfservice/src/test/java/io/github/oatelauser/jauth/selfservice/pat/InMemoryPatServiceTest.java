package io.github.oatelauser.jauth.selfservice.pat;

/**
 * {@link InMemoryPatService} 契约跑批（生产装配不注册本实现，契约照跑防漂移）。
 *
 * @author oatelauser
 */
class InMemoryPatServiceTest extends AbstractPatServiceContractTest {

    private final PatService service = new InMemoryPatService(fixedClock());

    @Override
    protected PatService service() {
        return this.service;
    }
}
