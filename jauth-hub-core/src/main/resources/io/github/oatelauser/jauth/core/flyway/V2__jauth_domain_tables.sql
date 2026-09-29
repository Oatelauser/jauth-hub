-- jauth 自有域表（票 07 数据模型定稿）
-- 双兼容纪律：主键/外键 UUID v7 一律 CHAR(36) 字符串；JSON/集合语义一律 TEXT（禁 jsonb）；
-- TIMESTAMP 无时区；无 PG 专属类型；引用 oauth2_registered_client.id 的列用 varchar(100) 与被引用列同型。
-- 词表列（role/status/event_type 等）不在 DB 层加 CHECK：词表归代码枚举所有（票 07），避免双库 CHECK 语法差异。

CREATE TABLE jauth_user (
    id CHAR(36) NOT NULL,
    username VARCHAR(50) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(100) DEFAULT NULL,
    email VARCHAR(255) DEFAULT NULL,
    role VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    -- sudo 位：最近一次强认证时间，NULL = 从未强认证（v1.2 Passkey/sudo 依赖）
    strong_auth_at TIMESTAMP DEFAULT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT jauth_user_username_uk UNIQUE (username)
);

-- Passkey 凭据：表随 v1.0 建、v1.2 启用（票 07）；credential_id 为 WebAuthn 原始 id 的 base64url
CREATE TABLE jauth_user_credential (
    id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    credential_id VARCHAR(512) NOT NULL,
    public_key TEXT NOT NULL,
    sign_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    last_used_at TIMESTAMP DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT jauth_user_credential_credential_id_uk UNIQUE (credential_id),
    CONSTRAINT jauth_user_credential_user_fk FOREIGN KEY (user_id) REFERENCES jauth_user (id) ON DELETE CASCADE
);

CREATE TABLE jauth_org (
    id CHAR(36) NOT NULL,
    name VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT jauth_org_name_uk UNIQUE (name)
);

-- org 是权限边界：一个用户可属多 org，安装审批权在 OWNER
CREATE TABLE jauth_org_member (
    org_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    role VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (org_id, user_id),
    CONSTRAINT jauth_org_member_org_fk FOREIGN KEY (org_id) REFERENCES jauth_org (id) ON DELETE CASCADE,
    CONSTRAINT jauth_org_member_user_fk FOREIGN KEY (user_id) REFERENCES jauth_user (id) ON DELETE CASCADE
);

-- 安装：client x org 的授权关系，ceiling_scopes 为发行 scope 封顶（请求 ∩ consent ∩ ceiling 运行时取交，不落表）
CREATE TABLE jauth_installation (
    id CHAR(36) NOT NULL,
    registered_client_id VARCHAR(100) NOT NULL,
    org_id CHAR(36) NOT NULL,
    status VARCHAR(16) NOT NULL,
    ceiling_scopes TEXT DEFAULT NULL,
    approved_by CHAR(36) DEFAULT NULL,
    approved_at TIMESTAMP DEFAULT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT jauth_installation_client_org_uk UNIQUE (registered_client_id, org_id),
    CONSTRAINT jauth_installation_client_fk FOREIGN KEY (registered_client_id) REFERENCES oauth2_registered_client (id) ON DELETE CASCADE,
    CONSTRAINT jauth_installation_org_fk FOREIGN KEY (org_id) REFERENCES jauth_org (id) ON DELETE CASCADE,
    CONSTRAINT jauth_installation_approver_fk FOREIGN KEY (approved_by) REFERENCES jauth_user (id)
);

-- PAT：只存 SHA-256（token_sha256 唯一）+ 展示前缀，明文令牌仅在创建响应中出现一次
CREATE TABLE jauth_pat (
    id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    token_sha256 CHAR(64) NOT NULL,
    token_prefix VARCHAR(20) NOT NULL,
    scopes TEXT NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    last_used_at TIMESTAMP DEFAULT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT jauth_pat_token_sha256_uk UNIQUE (token_sha256),
    CONSTRAINT jauth_pat_user_fk FOREIGN KEY (user_id) REFERENCES jauth_user (id) ON DELETE CASCADE
);

-- 刷新令牌族谱（RTR）：(user, client) 一族，轮转递增 generation；检测到重放整族烧断（status 置 REVOKED）
CREATE TABLE jauth_token_family (
    id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    registered_client_id VARCHAR(100) NOT NULL,
    generation INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT jauth_token_family_user_client_uk UNIQUE (user_id, registered_client_id),
    CONSTRAINT jauth_token_family_user_fk FOREIGN KEY (user_id) REFERENCES jauth_user (id) ON DELETE CASCADE,
    CONSTRAINT jauth_token_family_client_fk FOREIGN KEY (registered_client_id) REFERENCES oauth2_registered_client (id) ON DELETE CASCADE
);

-- 审计：追加只写（append-only），只记生命周期事件（签发/刷新/撤销/consent/登录/审批）；内省不打审计（票 07/P5）。
-- 无外键：目标行可先于事件被删，审计必须留痕
CREATE TABLE jauth_audit_event (
    id CHAR(36) NOT NULL,
    ts TIMESTAMP NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    actor_user_id CHAR(36) DEFAULT NULL,
    target_type VARCHAR(50) DEFAULT NULL,
    target_id VARCHAR(100) DEFAULT NULL,
    ip VARCHAR(45) DEFAULT NULL,
    user_agent VARCHAR(500) DEFAULT NULL,
    detail TEXT DEFAULT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX jauth_audit_event_ts_idx ON jauth_audit_event (ts);
CREATE INDEX jauth_audit_event_actor_idx ON jauth_audit_event (actor_user_id);
