-- B6：管理员令牌版本号，用于登出吊销（旧令牌失效）。
-- H2（MODE=PostgreSQL）与 PostgreSQL 均支持 ALTER TABLE ... ADD COLUMN ... DEFAULT。
ALTER TABLE admins ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;
