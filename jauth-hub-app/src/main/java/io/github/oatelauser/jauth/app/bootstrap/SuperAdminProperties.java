package io.github.oatelauser.jauth.app.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 首启超管属性（{@code jauth-hub.bootstrap.superadmin.*}，04 票：账号由 properties 定义、启动 seeding，
 * 非 Flyway 数据）。password 为明文播种值，经 {@code PasswordEncoder} 编码落库——生产写 ENC() 密文
 * （spring-plus config-encryption），密钥走环境变量。
 *
 * @author oatelauser
 */
@ConfigurationProperties("jauth-hub.bootstrap.superadmin")
public class SuperAdminProperties {

    private String username;

    private String password;

    public String getUsername() {
        return this.username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return this.password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
