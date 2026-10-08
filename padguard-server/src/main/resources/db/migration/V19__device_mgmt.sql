-- 设备管理增强：应用目录库 + 分发/升级记录 + 权限策略 + 功能策略。
-- 兼容 H2(MODE=PostgreSQL) 与 PostgreSQL；全部幂等。

CREATE TABLE IF NOT EXISTS app_catalog (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    package_name VARCHAR(256) NOT NULL,
    app_name VARCHAR(256),
    version_code BIGINT NOT NULL DEFAULT 0,
    version_name VARCHAR(128),
    file_id VARCHAR(64) NOT NULL,
    file_name VARCHAR(256),
    size_bytes BIGINT NOT NULL DEFAULT 0,
    icon_url VARCHAR(512),
    description TEXT,
    created_at BIGINT NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_app_catalog_user ON app_catalog(user_id);
CREATE INDEX IF NOT EXISTS idx_app_catalog_user_pkg ON app_catalog(user_id, package_name);

CREATE TABLE IF NOT EXISTS app_distribution (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    catalog_id VARCHAR(64),
    package_name VARCHAR(256) NOT NULL,
    version_code BIGINT NOT NULL DEFAULT 0,
    kind VARCHAR(16) NOT NULL DEFAULT 'INSTALL',
    msg_id VARCHAR(64),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    error_code VARCHAR(64),
    error_message TEXT,
    created_at BIGINT NOT NULL DEFAULT 0,
    finished_at BIGINT
);
CREATE INDEX IF NOT EXISTS idx_app_distribution_device ON app_distribution(device_id);
CREATE INDEX IF NOT EXISTS idx_app_distribution_user ON app_distribution(user_id);

CREATE TABLE IF NOT EXISTS device_permission_policy (
    device_id VARCHAR(64) PRIMARY KEY,
    target_package VARCHAR(256),
    granted_permissions TEXT,
    updated_at BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS device_feature_policy (
    device_id VARCHAR(64) PRIMARY KEY,
    features_json TEXT,
    updated_at BIGINT NOT NULL DEFAULT 0
);
