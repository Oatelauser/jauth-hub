package io.github.oatelauser.jauth.app.bootstrap;

import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import io.github.oatelauser.jauth.core.util.UuidV7;
import java.time.Instant;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * 首启超管播种器（04 票）：properties 定义账号，启动时建号；已存在即整条跳过——不覆盖、不报错，
 * 与 {@code ClientSeeder} 同一幂等语义（properties 是播种器不是第二真源，改密码走库不走 yml）。
 *
 * @author oatelauser
 */
public class SuperAdminSeeder {

    /** 构造期快照账号（SpotBugs EI_EXPOSE_REP2：properties 对象可变，字符串值落字段定死播种定义）。 */
    private final String username;

    private final String password;

    private final UserRepository userRepository;

    private final PasswordEncoder passwordEncoder;

    public SuperAdminSeeder(
            SuperAdminProperties properties, UserRepository userRepository, PasswordEncoder passwordEncoder) {
        Assert.notNull(properties, "properties cannot be null");
        Assert.notNull(userRepository, "userRepository cannot be null");
        Assert.notNull(passwordEncoder, "passwordEncoder cannot be null");
        this.username = properties.getUsername();
        this.password = properties.getPassword();
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 执行播种（幂等）。
     */
    public void seed() {
        if (!StringUtils.hasText(this.username) || !StringUtils.hasText(this.password)) {
            // fail-fast 优于静默跳过：壳层的登录全靠这个账号，缺配置属部署错误而非可选场景
            throw new IllegalStateException(
                    "jauth-hub.bootstrap.superadmin.username/password must be configured for first-boot seeding");
        }
        if (this.userRepository.findByUsername(this.username) != null) {
            return;
        }
        this.userRepository.save(new JauthUser(
                UuidV7.generate().toString(),
                this.username,
                this.passwordEncoder.encode(this.password),
                "Super Admin",
                null,
                JauthUser.ROLE_SUPERADMIN,
                JauthUser.STATUS_ACTIVE,
                null,
                Instant.now()));
    }
}
