package io.github.oatelauser.jauth.core.user;

import java.time.Clock;
import java.time.Duration;
import org.springframework.util.Assert;

/**
 * sudo 新鲜度判官（v1.2 C3，user 域邻接）：最近一次 passkey 强认证（{@code jauth_user.strong_auth_at}，
 * 由认证事件桥在 passkey 成功路径打点）距现在是否仍在 TTL 内。
 *
 * <p>纯查询无状态：判定依赖 UserRepository + Duration + Clock，可变钟直推窗口边界做单测；打点与判定分离
 * （打点在 core/audit 桥，判定在此，拦截在 selfservice）——三处各自可换实现互不牵连。
 *
 * <p>fail-closed 语义：用户不存在（主体不在 jauth 池）或从未强认证（NULL）都判不新鲜——拦截层无需再区分。
 *
 * @author oatelauser
 */
public class SudoGate {

    private final UserRepository userRepository;

    private final Duration ttl;

    private final Clock clock;

    public SudoGate(UserRepository userRepository, Duration ttl, Clock clock) {
        Assert.notNull(userRepository, "userRepository cannot be null");
        Assert.notNull(ttl, "ttl cannot be null");
        Assert.notNull(clock, "clock cannot be null");
        this.userRepository = userRepository;
        this.ttl = ttl;
        this.clock = clock;
    }

    /**
     * 该用户的强认证是否仍新鲜（TTL 内）。
     *
     * @param username 登录名（拦截层取自请求主体）
     * @return true = 放行；false 或用户不在池 = 拦（fail-closed）
     */
    public boolean isFresh(String username) {
        JauthUser user = this.userRepository.findByUsername(username);
        if (user == null || user.strongAuthAt() == null) {
            return false;
        }
        return user.strongAuthAt().plus(this.ttl).isAfter(this.clock.instant());
    }
}
