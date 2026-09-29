package io.github.oatelauser.jauth.core.token;

import java.util.Map;

/**
 * claims 贡献 SPI：向 id_token / userinfo / introspection 响应贡献键值（SPEC §3 内省富化： 三者走同一映射扩展点，默认携带
 * sub/username/scope/orgs——业务资源服务器细粒度鉴权的统一数据源）。
 *
 * <p>贡献面由装配层（B4）以两个 {@code OAuth2TokenCustomizer} 形态接出： opaque access token claims（内省端点读取）与 OIDC
 * id_token 编码（userinfo 与之共享 claims）。
 *
 * <p>约定：返回 null 或空 Map 即"无贡献"；键冲突时后注册的贡献者覆盖先注册者； 已知边界——orgs（含 org 角色）属 v1.1 平台层（org/installation
 * 域），届时在本 SPI 上追加实现， 接口形状无需变更（上下文已带 principal/client/scope 三要素）。
 *
 * @author oatelauser
 */
@FunctionalInterface
public interface ClaimsContributor {

    /**
     * 贡献 claims。
     *
     * @param context 令牌主体上下文（principal/client/scope）
     * @return 贡献的键值；无贡献返回 null 或空 Map
     */
    Map<String, Object> contribute(TokenClaimsContext context);
}
