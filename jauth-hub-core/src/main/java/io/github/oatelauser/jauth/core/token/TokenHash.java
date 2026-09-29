package io.github.oatelauser.jauth.core.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 令牌哈希工具：SHA-256 → 64 位小写十六进制。
 *
 * <p>用途是"落库即哈希"手术（SPEC §3）：opaque access/refresh/PAT 令牌只在内存中存在明文，
 * 数据库列（oauth2_authorization.*_value、jauth_pat.token_sha256）一律存本工具的输出， 查询侧先哈希再比对。SHA-256
 * 对该场景是正确选择：令牌本身是高熵随机值，无需抗碰撞口令哈希（bcrypt）。
 *
 * @author oatelauser
 */
public final class TokenHash {

    private TokenHash() {}

    /**
     * 计算令牌的 SHA-256 小写十六进制表示。
     *
     * @param rawToken 明文令牌，UTF-8 编码
     * @return 64 字符小写十六进制串
     */
    public static String sha256Hex(String rawToken) {
        Objects.requireNonNull(rawToken, "rawToken cannot be null");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            // JVM 规范强制提供 SHA-256，走到这里说明运行时残缺
            throw new IllegalStateException("SHA-256 algorithm is not available in this JVM", ex);
        }
    }
}
