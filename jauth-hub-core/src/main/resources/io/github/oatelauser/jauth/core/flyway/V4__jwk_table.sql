-- 签名密钥表（SPEC §3 第 14 表，B1 复审补定；支撑 §6 的 90d 轮转 + 14d 重叠 + 最多 2 把共存）
-- 双兼容纪律同 V1-V3：主键 CHAR(36)（UUID v7 字符串）、密钥材料 TEXT、TIMESTAMP 无时区、无 PG 专属类型。
-- 状态词表归代码枚举（ACTIVE|RETIRING|RETIRED，票 07），DB 层不加 CHECK。
-- 已知边界：private_key 明文落库，v1 取舍——DB 泄露面由部署侧（网络隔离/最小权限账号）收口，
-- 升级路径 = KMS 或可插拔 KeyStore（届时仅改 JwkRepository 读写两侧，列结构不动，不引入加密列）。
CREATE TABLE jauth_jwk (
    id CHAR(36) NOT NULL,
    kid VARCHAR(64) NOT NULL,
    algorithm VARCHAR(16) NOT NULL,
    key_size INT NOT NULL,
    -- public_key = X.509 SubjectPublicKeyInfo 的 Base64（JWKS 出网用）；private_key = PKCS#8 的 Base64（签名用）
    public_key TEXT NOT NULL,
    private_key TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    -- RETIRING 的最迟出网时间（转 RETIRING 时 = 当时 + 14d 重叠窗）；ACTIVE/RETIRED 为 NULL
    retire_after TIMESTAMP DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT jauth_jwk_kid_uk UNIQUE (kid)
);

CREATE INDEX jauth_jwk_status_created_idx ON jauth_jwk (status, created_at);
