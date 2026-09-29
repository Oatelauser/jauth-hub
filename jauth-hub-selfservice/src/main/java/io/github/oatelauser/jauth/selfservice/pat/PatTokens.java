package io.github.oatelauser.jauth.selfservice.pat;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * PAT 令牌形制：{@code jpat_} + 32 字节 SecureRandom 的 base64url（43 字符），共 48 字符、256 位熵——
 * 高熵随机值配 SHA-256 落库（{@code TokenHash} 类注释的选型论证），与 GitHub {@code ghp_} 同款做法。
 *
 * <p>展示前缀取明文头 12 字符（含 {@code jpat_} 头）：用户在列表里能认出"是哪一枚"，而剩余 36 字符不出现，
 * 前缀不可推回明文。
 *
 * @author oatelauser
 */
public final class PatTokens {

    /** 令牌可识别头：校验侧（B7 接线）据此区分 PAT 与普通 access token。 */
    public static final String TOKEN_HEADER = "jpat_";

    /** 展示前缀长度（表 jauth_pat.token_prefix，VARCHAR(20) 内）。 */
    public static final int DISPLAY_PREFIX_LENGTH = 12;

    /** 熵源：256 位（32 字节）。 */
    private static final int TOKEN_ENTROPY_BYTES = 32;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private PatTokens() {}

    /**
     * 生成一枚新明文令牌。
     *
     * @return {@code jpat_} + base64url(32 字节 SecureRandom)
     */
    public static String generate() {
        byte[] entropy = new byte[TOKEN_ENTROPY_BYTES];
        SECURE_RANDOM.nextBytes(entropy);
        return TOKEN_HEADER + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    }

    /**
     * 由明文推导展示前缀（落库 token_prefix 列）。
     *
     * @param rawToken 明文令牌
     * @return 头 {@value #DISPLAY_PREFIX_LENGTH} 字符
     */
    public static String displayPrefix(String rawToken) {
        return rawToken.substring(0, Math.min(DISPLAY_PREFIX_LENGTH, rawToken.length()));
    }
}
