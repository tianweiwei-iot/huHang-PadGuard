-- 应用监控 / 远程安装维护：设备已安装应用台账
--
-- 唯一约束 (device_id, package_name) 是幂等 upsert 的前提：
-- 孩子端周期性全量上报，服务端按包做"存在即更新、不存在即插入"，
-- 没有这个约束就只能在应用层先查后写，在高并发上报下会产生重复行。

CREATE TABLE IF NOT EXISTS installed_apps (
    id            VARCHAR(64) PRIMARY KEY,
    device_id     VARCHAR(64)  NOT NULL REFERENCES devices (id),
    package_name  VARCHAR(256) NOT NULL,
    app_name      VARCHAR(256),
    version_name  VARCHAR(64),
    version_code  BIGINT,
    is_system     BOOLEAN      NOT NULL DEFAULT FALSE,
    installed     BOOLEAN      NOT NULL DEFAULT TRUE,
    suspended     BOOLEAN      NOT NULL DEFAULT FALSE,
    install_time  BIGINT,
    update_time   BIGINT,
    first_seen_at BIGINT       NOT NULL DEFAULT 0,
    last_seen_at  BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_installed_apps_device_package UNIQUE (device_id, package_name)
);

-- 列表页按设备查询 + 按"是否系统应用/名称"排序，复合索引覆盖主查询路径
CREATE INDEX IF NOT EXISTS idx_installed_apps_device ON installed_apps (device_id, installed);
