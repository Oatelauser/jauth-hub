package io.github.oatelauser.jauth.selfservice.web;

import java.time.Instant;
import java.util.Set;

/**
 * 已授权应用看板的一行：某 principal 对某 client 的授权概览（按 client 聚合，非单条授权）。
 *
 * <p>client 展示名不在本记录内——由控制器经 RegisteredClientRepository 解析（查无回退 client id 本身，
 * 与 consent 页同款回退语义）。
 *
 * @author oatelauser
 */
public record AuthorizedApp(String registeredClientId, Set<String> scopes, Instant lastAuthorizedAt) {

    /** scopes 防御性拷贝为不可变集。 */
    public AuthorizedApp {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }
}
