-- 电子围栏由"单围栏（PK=device_id）"升级为"多围栏（一对多）"。
-- 仅 docker / Postgres（ddl-auto=none + Flyway）环境执行；本地 H2 由 ddl-auto=update
-- 按 GeofenceZone 实体自动建表，且本脚本不会执行，故旧 geofences 表在本地仅作为
-- 残留空表存在、无害。DO 块对旧表做存在性判断，避免全新环境（无 geofences）报"表不存在"。

CREATE TABLE IF NOT EXISTS geofence_zones (
    id            VARCHAR(64) PRIMARY KEY,
    device_id     VARCHAR(64) NOT NULL,
    enabled       BOOLEAN NOT NULL DEFAULT FALSE,
    name          VARCHAR(64),
    center_lat    DOUBLE PRECISION,
    center_lng    DOUBLE PRECISION,
    radius_meters INT,
    alert_on_exit BOOLEAN NOT NULL DEFAULT TRUE,
    alert_on_enter BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_geofence_zones_device ON geofence_zones (device_id);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'geofences') THEN
        INSERT INTO geofence_zones (id, device_id, enabled, name, center_lat, center_lng, radius_meters, alert_on_exit, alert_on_enter, created_at)
            SELECT gen_random_uuid()::text, device_id, enabled, name, center_lat, center_lng, radius_meters, alert_on_exit, FALSE, 0
            FROM geofences
            WHERE device_id IS NOT NULL;
        DROP TABLE geofences;
    END IF;
END $$;
