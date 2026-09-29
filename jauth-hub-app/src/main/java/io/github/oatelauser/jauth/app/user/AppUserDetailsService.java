package io.github.oatelauser.jauth.app.user;

import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.util.Assert;

/**
 * 壳层自持用户域（嵌入契约：app 即"宿主"，必须自备 {@link UserDetailsService}）：查 jauth_user 表映射
 * {@link UserDetails}，密码哈希原样透传（编码/校验归全局 {@code PasswordEncoder}）。
 *
 * <p>角色映射（08 票已核实事实 3）：{@code role=SUPERADMIN} → {@code ROLE_SUPER_ADMIN}（spring-plus 超管
 * 短路与 {@code @RequiresAdminRole} 语义即生效），其余映射 {@code ROLE_USER}；授权走 GrantedAuthority 路线，
 * 零代码对接。
 *
 * @author oatelauser
 */
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public AppUserDetailsService(UserRepository userRepository) {
        Assert.notNull(userRepository, "userRepository cannot be null");
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        JauthUser user = this.userRepository.findByUsername(username);
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在");
        }
        String authority = JauthUser.ROLE_SUPERADMIN.equals(user.role()) ? "ROLE_SUPER_ADMIN" : "ROLE_USER";
        return User.withUsername(user.username())
                .password(user.passwordHash())
                .disabled(!JauthUser.STATUS_ACTIVE.equals(user.status()))
                .authorities(authority)
                .build();
    }
}
