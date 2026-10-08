-- 家长账号资料补齐：email / region / remark。
-- 后台「新建 / 修改家长账号」表单收集这些信息，此前未落库（隐性数据丢失），本次补列并真正持久化。
-- 兼容 H2(MODE=PostgreSQL) 与 PostgreSQL；语句幂等（IF NOT EXISTS）。

ALTER TABLE users ADD COLUMN IF NOT EXISTS email VARCHAR(256);
ALTER TABLE users ADD COLUMN IF NOT EXISTS region VARCHAR(64);
ALTER TABLE users ADD COLUMN IF NOT EXISTS remark VARCHAR(512);
