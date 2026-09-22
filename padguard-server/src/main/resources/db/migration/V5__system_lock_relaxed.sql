-- 救援通道：device_settings 增加「临时解除系统设置锁定」开关
-- home/local profile（H2）由 ddl-auto=update 自动加列，此迁移用于 docker/postgres 环境
ALTER TABLE device_settings ADD COLUMN system_lock_relaxed BOOLEAN NOT NULL DEFAULT FALSE;
