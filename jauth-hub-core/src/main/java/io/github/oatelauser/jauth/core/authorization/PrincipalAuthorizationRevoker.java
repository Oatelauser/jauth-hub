package io.github.oatelauser.jauth.core.authorization;

/**
 * 按主体的授权全量清剿（v1.3 D1）：删除该主体在**全部 client** 下的 oauth2 授权行并把其刷新族谱整主体
 * 烧断——改密（自助/管理员重置）与停用账号后的 fail-secure 语义：凭据可能已在失窃窗口内换取过授权，
 * 账号状态变更时全部就地失效，各应用须重新走登录授权。
 *
 * <p>与烧族路径（{@link JauthJdbcOAuth2AuthorizationService} 的重放熔断，按 user+client 单族）的差别仅在
 * 范围：本接口按 principal 全量。两实现共享「先删授权、后烧族谱」的顺序——中断在任何一步都停在安全态
 * （授权已删即可用性消失），反序才会留下「族已 BURNED 但授权仍可用」的窗口。
 *
 * <p>已知盲区：若部署启用了 JWT 自包含 access token（非默认；v1 默认 opaque+内省），资源服务器本地验签
 * 不回查授权行，删除行对其不可见——默认配置无此面。
 *
 * <p>审计：逐令牌生命周期事件（token.revoked）由各实现路径自然产生（jdbc 直删路径无逐条事件），
 * 汇总事件（credentials.revoked，含 reason 与计数）由调用方（app 的编排服务）发布。
 *
 * @author oatelauser
 */
public interface PrincipalAuthorizationRevoker {

    /**
     * 清剿该主体的全部授权与族谱。
     *
     * @param principalName 主体名（= 登录名，oauth2_authorization.principal_name 口径）
     * @return 删除的授权行数（memory 实现为逐条移除计数）
     */
    int revokeAll(String principalName);
}
