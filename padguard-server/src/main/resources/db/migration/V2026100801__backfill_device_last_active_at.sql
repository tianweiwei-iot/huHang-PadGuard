-- 回填历史设备记录中 last_active_at 为 NULL 的行（新 schema 加列时未回填）。
-- 字段已改为可空，此迁移仅清存量脏数据，幂等。
UPDATE devices SET last_active_at = 0 WHERE last_active_at IS NULL;
