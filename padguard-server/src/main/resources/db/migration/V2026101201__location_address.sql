-- 定位轨迹增加文字地址列：服务端在落库时对 WGS-84 坐标做高德逆地理编码，回填地址文字。
-- 本地 H2（home/local profile）由 ddl-auto=update 自动加列，无需此脚本；
-- 此迁移供 docker/postgres 等 ddl-auto=none + Flyway 的外部环境使用。
-- IF NOT EXISTS 幂等：已上线环境不会重复加列，本地 H2 靠 ddl-auto 兜底。
ALTER TABLE location_tracks ADD COLUMN IF NOT EXISTS address VARCHAR(255);
