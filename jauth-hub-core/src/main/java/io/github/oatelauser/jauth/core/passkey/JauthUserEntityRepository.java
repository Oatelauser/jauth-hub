package io.github.oatelauser.jauth.core.passkey;

import io.github.oatelauser.jauth.core.user.JauthUser;
import io.github.oatelauser.jauth.core.user.UserRepository;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.util.Assert;

/**
 * SS7 WebAuthn 用户句柄仓储的 jauth 适配（v1.2 C1）：用户面单一真源在 {@code jauth_user}，
 * 本适配只做"框架的 {@link PublicKeyCredentialUserEntity} 视图"，不另存用户。
 *
 * <p><b>user handle = {@code jauth_user.id}（UUIDv7 字符串）的 UTF-8 {@link Bytes}</b>：
 * 认证回路里凭据行（{@code user_id} 列）与用户实体（{@code id} 主键）由此同键闭环；
 * UTF-8 编解码无损，且与 {@code jauth_user_credential.user_id} CHAR(36) 列的存法一致。
 *
 * <p><b>save/delete 为 no-op</b>：框架侧唯一调用方是
 * {@code Webauthn4JRelyingPartyOperations.findUserEntityOrCreateAndSave}——仅当按登录名查不到用户实体时的
 * 兜底"随手建一个随机句柄"；jauth 的用户恒先于 passkey 存在（表单登录/宿主用户体系先建用户），该兜底
 * 实际不可达。即便被调用（如用户刚被删的竞态），no-op 也保持 jauth_user 单一真源——不从 WebAuthn 侧建用户。
 *
 * @author oatelauser
 */
public class JauthUserEntityRepository implements PublicKeyCredentialUserEntityRepository {

    private final UserRepository userRepository;

    public JauthUserEntityRepository(UserRepository userRepository) {
        Assert.notNull(userRepository, "userRepository cannot be null");
        this.userRepository = userRepository;
    }

    @Override
    public @Nullable PublicKeyCredentialUserEntity findById(Bytes id) {
        Assert.notNull(id, "id cannot be null");
        JauthUser user = this.userRepository.findById(userHandle(id));
        return user == null ? null : toUserEntity(user);
    }

    @Override
    public @Nullable PublicKeyCredentialUserEntity findByUsername(String username) {
        Assert.hasText(username, "username cannot be empty");
        JauthUser user = this.userRepository.findByUsername(username);
        return user == null ? null : toUserEntity(user);
    }

    @Override
    public void save(PublicKeyCredentialUserEntity userEntity) {
        // no-op，理由见类注释：用户真源在 jauth_user，WebAuthn 注册不建用户
    }

    @Override
    public void delete(Bytes id) {
        // no-op，理由见类注释：用户生命周期归 jauth 用户体系（级联删凭据由外键 ON DELETE CASCADE 承担）
    }

    /** 用户实体视图：name=登录名（认证回路 loadUserByUsername 的键），displayName 空时回退登录名。 */
    private static PublicKeyCredentialUserEntity toUserEntity(JauthUser user) {
        String displayName = user.displayName() == null ? user.username() : user.displayName();
        return ImmutablePublicKeyCredentialUserEntity.builder()
                .id(userHandle(user.id()))
                .name(user.username())
                .displayName(displayName)
                .build();
    }

    /** 句柄 ↔ 用户 id 的唯一编解码点（UTF-8）。 */
    static String userHandle(Bytes id) {
        return new String(id.getBytes(), StandardCharsets.UTF_8);
    }

    static Bytes userHandle(String userId) {
        return new Bytes(userId.getBytes(StandardCharsets.UTF_8));
    }
}
