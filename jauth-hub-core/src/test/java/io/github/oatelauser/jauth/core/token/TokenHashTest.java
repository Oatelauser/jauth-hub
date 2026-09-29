package io.github.oatelauser.jauth.core.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * {@link TokenHash} 已知向量单测（向量取自 FIPS 180-2 与 sha256sum 实算）。
 *
 * @author oatelauser
 */
class TokenHashTest {

    private static final String TEST_TOKEN_PLACEHOLDER = "jauth-hub-b1-vector-token";

    @Test
    void hashesKnownVectorsToLowercaseHex() {
        assertThat(TokenHash.sha256Hex(""))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(TokenHash.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(TokenHash.sha256Hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"))
                .isEqualTo("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1");
        assertThat(TokenHash.sha256Hex(TEST_TOKEN_PLACEHOLDER))
                .isEqualTo("603e52b1f1dda5e794ff802328854465e5bef8fddf0257eed3103bde783fc2a3");
    }

    @Test
    void outputIs64LowercaseHexChars() {
        String hash = TokenHash.sha256Hex(TEST_TOKEN_PLACEHOLDER);
        assertThat(hash).matches("[0-9a-f]{64}");
    }

    @Test
    void deterministicForSameInput() {
        assertThat(TokenHash.sha256Hex(TEST_TOKEN_PLACEHOLDER)).isEqualTo(TokenHash.sha256Hex(TEST_TOKEN_PLACEHOLDER));
    }

    @Test
    void rejectsNullInput() {
        assertThatThrownBy(() -> TokenHash.sha256Hex(null)).isInstanceOf(NullPointerException.class);
    }
}
