-- B8 安装两步制（流向 A 拍板）：安装请求补发起人/请求 scopes 两列
-- 双兼容纪律：两条独立 ALTER（H2/PG 皆支持单列 ADD COLUMN）；词表/集合语义照旧不加 CHECK，
-- requested_scopes 为 TEXT 空格分隔（scope 序列化惯例同 client/PAT 的 scopes 列）。
ALTER TABLE jauth_installation ADD COLUMN requested_by CHAR(36) DEFAULT NULL;
ALTER TABLE jauth_installation ADD COLUMN requested_scopes TEXT DEFAULT NULL;

-- 发起人外键：镜像 V2 里 approved_by 的 FK 写法（不加 ON DELETE：审计/审批留痕语义，用户删除不级联清安装）
ALTER TABLE jauth_installation
    ADD CONSTRAINT jauth_installation_requester_fk FOREIGN KEY (requested_by) REFERENCES jauth_user (id);
