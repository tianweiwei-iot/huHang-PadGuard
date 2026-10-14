-- 定位轨迹增加 fresh 列：标记该点是否为"实时采集"（true）还是"缓存降级点"（false）。
-- 用于家长端区分"刚拿到的位置"与"几分钟/几小时前的旧位置"，避免历史位置被误显示为实时。
-- 本地 H2（home/local profile）由 ddl-auto=update 自动加列，无需此脚本；
-- 此迁移供 docker/postgres 等 ddl-auto=none + Flyway 的外部环境使用。
-- IF NOT EXISTS + DEFAULT TRUE 幂等：已上线环境不会重复加列，存量行视为"新鲜"不误报警。
ALTER TABLE location_tracks ADD COLUMN IF NOT EXISTS fresh BOOLEAN DEFAULT TRUE;
