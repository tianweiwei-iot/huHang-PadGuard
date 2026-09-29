-- 时间管控总开关：DeviceSetting 扩展字段
-- docker/postgres 环境由 Flyway 执行；h2（local profile）由 ddl-auto=update 自动加列，无需此脚本。
ALTER TABLE device_settings ADD COLUMN IF NOT EXISTS time_control_enabled BOOLEAN NOT NULL DEFAULT TRUE;
