-- 未成年人模式分龄档位（P1 合规底座）
-- docker/postgres 环境由 Flyway 执行；h2（local / home profile）由 ddl-auto=update 自动加列，无需此脚本。
ALTER TABLE devices ADD COLUMN IF NOT EXISTS age_band VARCHAR(32);
