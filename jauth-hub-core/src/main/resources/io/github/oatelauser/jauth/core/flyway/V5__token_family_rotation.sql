-- RTR 族谱行模型升级（B2）：V2 把族谱钉在 UNIQUE(user_id, registered_client_id) 一行上，
-- 只能记"族当前状态"，无法保留历代 refresh token 哈希——而重放检测恰恰要求"任何一代旧哈希都查得到"。
-- 变更 1：UNIQUE(user_id, registered_client_id) 撤销、降为普通索引——族谱变一轮转一行，烧族/取最新代按它聚合。
-- 变更 2：补 refresh_token_hash CHAR(64) 全局唯一——findByToken 未命中时按历史哈希探测重放（探测键）。
-- 变更 3：user_id（CHAR(36) 外键 jauth_user.id）改 principal_name（VARCHAR(200)，与 oauth2_authorization.principal_name
--         同宽）：框架授权表只有 principal_name，没有 user id；族谱与授权表共用同一身份键，
--         烧族才能一条 DELETE 清掉该 user+client 的全部授权。username 全局唯一且 v1 无改名，语义等价 user；
--         jauth_user 外键随之去除（同审计表先例：目标行可先于引用删除，族谱作为墓碑必须留痕）。
-- 注：ADD COLUMN NOT NULL 仅在表空时可行——v1.0 未发布、jauth_token_family 无存量行（B1 建表后尚未写入）。
ALTER TABLE jauth_token_family DROP CONSTRAINT jauth_token_family_user_client_uk;
ALTER TABLE jauth_token_family DROP CONSTRAINT jauth_token_family_user_fk;
ALTER TABLE jauth_token_family DROP COLUMN user_id;
ALTER TABLE jauth_token_family ADD COLUMN principal_name VARCHAR(200) NOT NULL;
ALTER TABLE jauth_token_family ADD COLUMN refresh_token_hash CHAR(64) NOT NULL;
ALTER TABLE jauth_token_family ADD CONSTRAINT jauth_token_family_refresh_hash_uk UNIQUE (refresh_token_hash);
CREATE INDEX jauth_token_family_family_idx ON jauth_token_family (principal_name, registered_client_id);
