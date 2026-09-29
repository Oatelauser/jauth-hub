package io.github.oatelauser.jauth.core.token.key;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import org.jspecify.annotations.Nullable;

/**
 * 签名密钥实体（表 jauth_jwk，SPEC §3 第 14 表）。
 *
 * <p>密钥材料以标准编码的 Base64 文本落库：{@code publicKey} 为 X.509 SubjectPublicKeyInfo（JWKS 出网只依赖它）， {@code
 * privateKey} 为 PKCS#8（签名侧需要）。私钥列明文落库是 v1 取舍，升级路径见 V4 迁移注释。
 *
 * <p>状态词表（票 07：词表归代码所有，DB 层不加 CHECK）：ACTIVE（唯一签名钥）/ RETIRING（重叠期内仅验签出网）/ RETIRED（不再出网，仅留痕）。
 *
 * @param id 主键，UUID v7 字符串
 * @param kid JWKS kid，全局唯一（生成时 UUID v7）
 * @param algorithm 签名算法（当前恒 RS256）
 * @param keySize RSA 密钥位长（2048）
 * @param publicKey X.509 SPKI Base64
 * @param privateKey PKCS#8 Base64
 * @param status ACTIVE / RETIRING / RETIRED
 * @param createdAt 生成时间
 * @param retireAfter RETIRING 的最迟出网时间（当时 + 14d 重叠窗），其余状态为 NULL
 * @author oatelauser
 */
public record JwkRecord(
        String id,
        String kid,
        String algorithm,
        int keySize,
        String publicKey,
        String privateKey,
        String status,
        Instant createdAt,
        @Nullable Instant retireAfter) {

    public static final String STATUS_ACTIVE = "ACTIVE";

    public static final String STATUS_RETIRING = "RETIRING";

    public static final String STATUS_RETIRED = "RETIRED";

    /**
     * 重建为 nimbus 可用的 RSA JWK（含私钥，签名与 JWKS 出网共用）。
     *
     * @return kid/算法/用途齐备的 RSAKey
     * @throws IllegalStateException 库中密钥材料损坏（Base64/编码不合法）——属存储损坏，快速失败优于半运行
     */
    public RSAKey toRsaJwk() {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            RSAPublicKey publicKey = (RSAPublicKey) keyFactory.generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey())));
            RSAPrivateKey privateKey = (RSAPrivateKey) keyFactory.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey())));
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(kid())
                    .algorithm(JWSAlgorithm.parse(algorithm()))
                    .keyUse(KeyUse.SIGNATURE)
                    .build();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | IllegalArgumentException ex) {
            throw new IllegalStateException("JWK material for kid '" + kid() + "' is corrupt in jauth_jwk", ex);
        }
    }
}
