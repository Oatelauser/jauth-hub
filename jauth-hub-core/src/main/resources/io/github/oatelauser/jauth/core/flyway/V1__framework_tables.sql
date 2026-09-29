-- 框架三表（oauth2_registered_client / oauth2_authorization / oauth2_authorization_consent）
-- 底本：spring-security-oauth2-authorization-server 7.1.1 的 oauth2-*-schema.sql（jar 内资源，勿凭记忆改写框架列）。
-- H2/PostgreSQL 双兼容改造（SPEC §1 双兼容 SQL 纪律）：
--   1. blob -> text：PG 无 blob 类型（框架自身的 PG 指引即改为 text），H2 与 PG 均接受 text；
--      框架 JdbcOAuth2AuthorizationService 按列元数据自适应 VARCHAR/CLOB 读写路径，不依赖 blob。
--   2. timestamp 保持无时区：两种库语义一致（禁用 timestamptz 宏，SPEC 双兼容纪律）。
--   3. oauth2_registered_client 追加 owner 两列：个人/组织应用二选一，皆空 = 平台内置客户端（内省机密客户端等）。

CREATE TABLE oauth2_registered_client (
    id varchar(100) NOT NULL,
    client_id varchar(100) NOT NULL,
    client_id_issued_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
    client_secret varchar(200) DEFAULT NULL,
    client_secret_expires_at timestamp DEFAULT NULL,
    client_name varchar(200) NOT NULL,
    client_authentication_methods varchar(1000) NOT NULL,
    authorization_grant_types varchar(1000) NOT NULL,
    redirect_uris varchar(1000) DEFAULT NULL,
    post_logout_redirect_uris varchar(1000) DEFAULT NULL,
    scopes varchar(1000) NOT NULL,
    client_settings varchar(2000) NOT NULL,
    token_settings varchar(2000) NOT NULL,
    owner_user_id CHAR(36) DEFAULT NULL,
    owner_org_id CHAR(36) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT oauth2_registered_client_client_id_uk UNIQUE (client_id),
    -- 至多一非空（SPEC §3）：允许皆空（平台内置），禁止皆设（一个应用不能同时归属个人与组织）
    CONSTRAINT oauth2_registered_client_owner_ck CHECK (owner_user_id IS NULL OR owner_org_id IS NULL)
);

-- 令牌值列（*_value）列名列宽照抄框架；实际落库内容为 SHA-256 小写十六进制（64 字符），
-- 由 JauthJdbcOAuth2AuthorizationService 在 save/findByToken 两侧做哈希手术，DB 永不见明文令牌。
CREATE TABLE oauth2_authorization (
    id varchar(100) NOT NULL,
    registered_client_id varchar(100) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorization_grant_type varchar(100) NOT NULL,
    authorized_scopes varchar(1000) DEFAULT NULL,
    attributes text DEFAULT NULL,
    state varchar(500) DEFAULT NULL,
    authorization_code_value text DEFAULT NULL,
    authorization_code_issued_at timestamp DEFAULT NULL,
    authorization_code_expires_at timestamp DEFAULT NULL,
    authorization_code_metadata text DEFAULT NULL,
    access_token_value text DEFAULT NULL,
    access_token_issued_at timestamp DEFAULT NULL,
    access_token_expires_at timestamp DEFAULT NULL,
    access_token_metadata text DEFAULT NULL,
    access_token_type varchar(100) DEFAULT NULL,
    access_token_scopes varchar(1000) DEFAULT NULL,
    oidc_id_token_value text DEFAULT NULL,
    oidc_id_token_issued_at timestamp DEFAULT NULL,
    oidc_id_token_expires_at timestamp DEFAULT NULL,
    oidc_id_token_metadata text DEFAULT NULL,
    refresh_token_value text DEFAULT NULL,
    refresh_token_issued_at timestamp DEFAULT NULL,
    refresh_token_expires_at timestamp DEFAULT NULL,
    refresh_token_metadata text DEFAULT NULL,
    user_code_value text DEFAULT NULL,
    user_code_issued_at timestamp DEFAULT NULL,
    user_code_expires_at timestamp DEFAULT NULL,
    user_code_metadata text DEFAULT NULL,
    device_code_value text DEFAULT NULL,
    device_code_issued_at timestamp DEFAULT NULL,
    device_code_expires_at timestamp DEFAULT NULL,
    device_code_metadata text DEFAULT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE oauth2_authorization_consent (
    registered_client_id varchar(100) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorities varchar(1000) NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name)
);
