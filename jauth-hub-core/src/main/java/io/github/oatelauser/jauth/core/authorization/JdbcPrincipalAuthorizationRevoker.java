package io.github.oatelauser.jauth.core.authorization;

import io.github.oatelauser.jauth.core.token.JdbcTokenFamilyService;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.util.Assert;

/**
 * 授权全量清剿的 JDBC 实现（v1.3 D1，jdbc 存储模式装配）：一条 DELETE 收掉该主体全部授权行
 * （access/refresh/设备码等同表同列，一并失效，内省即 inactive），随后整主体烧断族谱。
 *
 * <p>不走授权服务装饰链（审计装饰只观察 save/remove 单授权生命周期，无按主体面）——本类就是
 * 按主体面的那条路径；汇总审计事件由 app 编排层发布。
 *
 * @author oatelauser
 */
public class JdbcPrincipalAuthorizationRevoker implements PrincipalAuthorizationRevoker {

    private static final String DELETE_AUTHORIZATIONS = "DELETE FROM oauth2_authorization WHERE principal_name = ?";

    private final JdbcOperations jdbcOperations;

    private final JdbcTokenFamilyService tokenFamilyService;

    public JdbcPrincipalAuthorizationRevoker(JdbcOperations jdbcOperations, JdbcTokenFamilyService tokenFamilyService) {
        Assert.notNull(jdbcOperations, "jdbcOperations cannot be null");
        Assert.notNull(tokenFamilyService, "tokenFamilyService cannot be null");
        this.jdbcOperations = jdbcOperations;
        this.tokenFamilyService = tokenFamilyService;
    }

    @Override
    public int revokeAll(String principalName) {
        Assert.hasText(principalName, "principalName cannot be empty");
        // 先删授权后烧族（接口注释的顺序约束）：两步非事务，中断停在安全态
        int deleted = this.jdbcOperations.update(DELETE_AUTHORIZATIONS, principalName);
        this.tokenFamilyService.burnAllByPrincipal(principalName);
        return deleted;
    }
}
