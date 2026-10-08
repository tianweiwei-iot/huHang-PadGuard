-- PadGuard 服务端子账号（孩子）个人资料扩展 (V16)
-- 孩子端可自定义 姓名 / 昵称 / 头像，实时同步至服务端与家长端。
-- 原编号 V2 与 V2__feature_tables.sql 版本号冲突（Flyway 不允许同版本多文件），
-- 顺延至 V16 以消除重复版本导致的迁移启动失败。

ALTER TABLE devices ADD COLUMN IF NOT EXISTS child_name VARCHAR(64);
ALTER TABLE devices ADD COLUMN IF NOT EXISTS child_nickname VARCHAR(64);
ALTER TABLE devices ADD COLUMN IF NOT EXISTS child_avatar VARCHAR(512);
