-- 平板使用时间设置（家长端「时间管控」页）：DeviceSetting 扩展字段
-- home/local profile（H2）由 ddl-auto=update 自动加列，此迁移用于 docker/postgres 环境
ALTER TABLE device_settings ADD COLUMN weekday_limit_minutes INTEGER NOT NULL DEFAULT 120;
ALTER TABLE device_settings ADD COLUMN weekend_limit_minutes INTEGER NOT NULL DEFAULT 180;
ALTER TABLE device_settings ADD COLUMN rest_after_minutes INTEGER NOT NULL DEFAULT 60;
ALTER TABLE device_settings ADD COLUMN rest_duration_minutes INTEGER NOT NULL DEFAULT 15;
ALTER TABLE device_settings ADD COLUMN time_up_message TEXT;
ALTER TABLE device_settings ADD COLUMN enabled_time_ranges TEXT;
