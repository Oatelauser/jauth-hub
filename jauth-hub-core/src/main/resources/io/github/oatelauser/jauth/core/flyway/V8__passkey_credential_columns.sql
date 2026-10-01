-- v1.2 Passkey 启用补列（C1）：jauth_user_credential v1.0 建表（V2）、v1.2 启用（票 07）。
-- 为什么：SS7 CredentialRecord 的完整字段面超出 V2 的 7 列，注册（WebAuthnRegistrationFilter → 仓储 save）
-- 与认证（Webauthn4JRelyingPartyOperations.authenticate 回读 attestation 验签 + 刷 signCount/lastUsed）都依赖这些列。
-- 列型纪律：二进制列一律 Base64 TEXT（V4 jwk 先例，不用 BLOB——H2/PG BLOB 语义有差异）；transports 逗号连接
-- 序列化进 VARCHAR（词表归代码枚举 AuthenticatorTransport，DB 不加 CHECK）。
-- 语句形态：多列无 H2/PG 双兼容的单语句写法（H2 只认 ADD COLUMN (a, b) 括号列夹，PG 只认 ADD COLUMN a, ADD COLUMN b
-- 重复动作式），故逐列一条 ALTER（每条即 V7 先例的形态）；NOT NULL 列全部带 DEFAULT，存量行（空表）无感。
ALTER TABLE jauth_user_credential ADD COLUMN label VARCHAR(100) DEFAULT NULL;
ALTER TABLE jauth_user_credential ADD COLUMN credential_type VARCHAR(32) NOT NULL DEFAULT 'public-key';
ALTER TABLE jauth_user_credential ADD COLUMN backup_eligible BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE jauth_user_credential ADD COLUMN backup_state BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE jauth_user_credential ADD COLUMN uv_initialized BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE jauth_user_credential ADD COLUMN transports VARCHAR(256) DEFAULT NULL;
ALTER TABLE jauth_user_credential ADD COLUMN attestation_object TEXT DEFAULT NULL;
ALTER TABLE jauth_user_credential ADD COLUMN attestation_client_data_json TEXT DEFAULT NULL;
