package io.github.oatelauser.jauth.core.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * owner 解析契约测试（抽象基类，B9）：登记即可查、未知 id 返回 null、平台语义两列皆空——同一套断言跑
 * InMemory 与 JDBC 两实现（B8 契约模式）。
 *
 * @author oatelauser
 */
abstract class AbstractClientOwnerResolverContractTest {

    private static final String ORG_CLIENT_ID = "018f0000-0000-7000-8000-0000000000c1";

    private static final String USER_CLIENT_ID = "018f0000-0000-7000-8000-0000000000c2";

    @Test
    void registeredOwnersAreFindable() {
        ClientOwnerResolver resolver = resolver();
        seed(ORG_CLIENT_ID, ClientOwner.ofOrg("org-1"));
        seed(USER_CLIENT_ID, ClientOwner.ofUser("user-1"));

        assertThat(resolver.findOwner(ORG_CLIENT_ID)).isEqualTo(ClientOwner.ofOrg("org-1"));
        assertThat(resolver.findOwner(USER_CLIENT_ID)).isEqualTo(ClientOwner.ofUser("user-1"));
    }

    @Test
    void platformOwnerRoundTripsBothColumnsEmpty() {
        seed(ORG_CLIENT_ID, ClientOwner.platform());

        assertThat(resolver().findOwner(ORG_CLIENT_ID)).isEqualTo(ClientOwner.platform());
    }

    @Test
    void unknownClientIdReturnsNull() {
        assertThat(resolver().findOwner("018f0000-0000-7000-8000-0000000000ff")).isNull();
    }

    /** 活动解析器（seed 已写入其后生效，实现自持登记/建表细节）。 */
    protected abstract ClientOwnerResolver resolver();

    /** 登记归属（同 id 重复登记即覆盖）。 */
    protected abstract void seed(String registeredClientId, ClientOwner owner);
}
