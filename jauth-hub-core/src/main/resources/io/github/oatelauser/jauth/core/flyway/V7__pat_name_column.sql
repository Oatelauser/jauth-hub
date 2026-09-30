-- B10 PAT 名称列（2026-09-30 拍板）：创建面补名称输入，列表可辨识"是哪一枚"。
-- 存量行 NULL = 未命名，展示回退 i18n"未命名"；双兼容单条 ALTER（H2/PG 皆支持 ADD COLUMN 带 DEFAULT）。
ALTER TABLE jauth_pat ADD COLUMN name VARCHAR(100) DEFAULT NULL;
