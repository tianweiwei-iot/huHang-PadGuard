-- 补偿迁移：修复 geofence_zones 中可能存在的 NULL 坐标 / 半径。
--
-- 背景：V2026101202 的 INSERT…SELECT 直接搬运旧 geofences 表的 center_lat / center_lng /
-- radius_meters，若旧列含 NULL，新表对应列也会是 NULL；而 GeofenceService.detectTransitions
-- 对 NULL 的中心/半径会 `continue` 跳过该围栏，导致迁移后围栏"静默失效"且不报任何错。
--
-- 本脚本对存量 NULL 行就地补默认值（中心 0,0 / 半径 500），使围栏重新进入检测；
-- 幂等：仅命中 NULL 行，重复执行安全（Flyway 版本化，本脚本本身只跑一次）。
-- 注：V2026101202 的迁移只在 docker / Postgres（ddl-auto=none + Flyway）环境执行；
-- 本地 H2 由 ddl-auto=update 自动建表且本脚本不会执行，故本地环境无需此补偿。

UPDATE geofence_zones
SET center_lat    = COALESCE(center_lat, 0),
    center_lng    = COALESCE(center_lng, 0),
    radius_meters = COALESCE(radius_meters, 500)
WHERE center_lat IS NULL
   OR center_lng IS NULL
   OR radius_meters IS NULL;
