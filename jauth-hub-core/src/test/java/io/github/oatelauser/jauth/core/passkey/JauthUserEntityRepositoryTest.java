package io.github.oatelauser.jauth.core.passkey;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oatelauser.jauth.core.user.InMemoryUserRepository;
import io.github.oatelauser.jauth.core.user.JauthUser;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;

/**
 * 用户句柄仓储适配单测：jauth_user ↔ PublicKeyCredentialUserEntity 视图转换（句柄 = id 的 UTF-8 Bytes，
 * displayName 空回退登录名，save/delete no-op 不建不删用户）。
 *
 * @author oatelauser
 */
class JauthUserEntityRepositoryTest {

    private static final String USER_ID = "018f0000-0000-7000-8000-0000000000aa";

    private static final JauthUser USER_WITH_DISPLAY_NAME = new JauthUser(
            USER_ID,
            "alice",
            "{bcrypt}placeholder-not-a-real-hash",
            "Alice Display",
            null,
            JauthUser.ROLE_USER,
            JauthUser.STATUS_ACTIVE,
            null,
            Instant.parse("2026-10-01T07:00:00Z"));

    private InMemoryUserRepository userRepository;

    private JauthUserEntityRepository repository;

    @BeforeEach
    void setUp() {
        this.userRepository = new InMemoryUserRepository();
        this.userRepository.save(USER_WITH_DISPLAY_NAME);
        this.repository = new JauthUserEntityRepository(this.userRepository);
    }

    @Test
    void findByUsernameMapsToUserEntityView() {
        PublicKeyCredentialUserEntity entity = this.repository.findByUsername("alice");

        assertThat(entity).isNotNull();
        assertThat(entity.getName()).isEqualTo("alice");
        assertThat(entity.getDisplayName()).isEqualTo("Alice Display");
        assertThat(entity.getId()).isEqualTo(JauthUserEntityRepository.userHandle(USER_ID));
    }

    @Test
    void findByIdRoundTripsTheUserHandle() {
        assertThat(this.repository.findById(JauthUserEntityRepository.userHandle(USER_ID)))
                .isNotNull()
                .extracting(PublicKeyCredentialUserEntity::getName)
                .isEqualTo("alice");
    }

    @Test
    void displayNameFallsBackToUsernameWhenAbsent() {
        this.userRepository.save(new JauthUser(
                "018f0000-0000-7000-8000-0000000000bb",
                "bob",
                "{bcrypt}placeholder-not-a-real-hash",
                null,
                null,
                JauthUser.ROLE_USER,
                JauthUser.STATUS_ACTIVE,
                null,
                Instant.parse("2026-10-01T07:00:00Z")));

        assertThat(this.repository.findByUsername("bob").getDisplayName()).isEqualTo("bob");
    }

    @Test
    void missingLookupsReturnNull() {
        assertThat(this.repository.findByUsername("nobody")).isNull();
        assertThat(this.repository.findById(
                        JauthUserEntityRepository.userHandle("018f0000-0000-7000-8000-0000000000cc")))
                .isNull();
    }

    @Test
    void saveAndDeleteLeaveUserStoreUntouched() {
        this.repository.delete(JauthUserEntityRepository.userHandle(USER_ID));

        assertThat(this.userRepository.findByUsername("alice")).isNotNull();
    }
}
