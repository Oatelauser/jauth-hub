package io.github.oatelauser.jauth.app.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.oatelauser.jauth.core.user.InMemoryUserRepository;
import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * 壳层用户详情服务单测（B12）：role→authority 映射（SUPERADMIN→ROLE_SUPER_ADMIN，spring-plus 超管门的
 * 数据源）与 status→enabled 接线（DISABLED 必须产出 disabled 凭据——停用挡登录的机制位）。
 *
 * @author oatelauser
 */
class AppUserDetailsServiceTest {

    /** 占位哈希：非真实凭据，仅映射验证用。 */
    private static final String PLACEHOLDER_HASH = "{bcrypt}placeholder-not-a-real-hash";

    @Test
    @DisplayName("SUPERADMIN+ACTIVE：authority 为 ROLE_SUPER_ADMIN，凭据启用")
    void superadminActiveMapsToSuperAdminAuthorityEnabled() {
        UserRepository users = mock(UserRepository.class);
        when(users.findByUsername("root")).thenReturn(user("root", JauthUser.ROLE_SUPERADMIN, JauthUser.STATUS_ACTIVE));

        UserDetails details = new AppUserDetailsService(users).loadUserByUsername("root");

        assertThat(details.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_SUPER_ADMIN");
        assertThat(details.isEnabled()).as("ACTIVE 用户凭据可用").isTrue();
        assertThat(details.getPassword()).isEqualTo(PLACEHOLDER_HASH);
    }

    @Test
    @DisplayName("USER 角色：authority 为 ROLE_USER")
    void userRoleMapsToUserAuthority() {
        UserRepository users = mock(UserRepository.class);
        when(users.findByUsername("alice")).thenReturn(user("alice", JauthUser.ROLE_USER, JauthUser.STATUS_ACTIVE));

        UserDetails details = new AppUserDetailsService(users).loadUserByUsername("alice");

        assertThat(details.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("DISABLED：凭据 disabled——停用挡登录的接线位（管理页停用即时生效的机制）")
    void disabledUserYieldsDisabledCredentials() {
        UserRepository users = mock(UserRepository.class);
        when(users.findByUsername("bob")).thenReturn(user("bob", JauthUser.ROLE_USER, JauthUser.STATUS_DISABLED));

        UserDetails details = new AppUserDetailsService(users).loadUserByUsername("bob");

        assertThat(details.isEnabled()).as("DISABLED 用户凭据不可用，登录被拒").isFalse();
    }

    @Test
    @DisplayName("用户不存在：UsernameNotFoundException")
    void unknownUserThrows() {
        AppUserDetailsService service = new AppUserDetailsService(new InMemoryUserRepository());

        assertThatThrownBy(() -> service.loadUserByUsername("nobody")).isInstanceOf(UsernameNotFoundException.class);
    }

    private static JauthUser user(String username, String role, String status) {
        return new JauthUser(
                "018f0000-0000-7000-8000-0000000000aa",
                username,
                PLACEHOLDER_HASH,
                null,
                null,
                role,
                status,
                null,
                Instant.parse("2026-09-30T08:00:00Z"));
    }
}
