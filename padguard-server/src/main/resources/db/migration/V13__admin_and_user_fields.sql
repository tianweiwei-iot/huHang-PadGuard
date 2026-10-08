-- 管控后台（admin）落地：管理员账号、可编辑 RBAC、系统设置，以及家长账号状态列。
-- 兼容 H2 与 PostgreSQL；所有语句幂等（IF NOT EXISTS / IF COLUMNS 不存在才加）。

-- 家长账号增加 状态 / 最近登录
ALTER TABLE users ADD COLUMN IF NOT EXISTS status VARCHAR(20);
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_login_at BIGINT;

-- 管理员账号
CREATE TABLE IF NOT EXISTS admins (
    id VARCHAR(64) PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(256) NOT NULL,
    display_name VARCHAR(128),
    email VARCHAR(256),
    role_key VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    last_login_at BIGINT,
    created_at BIGINT
);

-- 角色权限覆盖（可编辑 RBAC 持久化）
CREATE TABLE IF NOT EXISTS admin_role_overrides (
    id VARCHAR(64) PRIMARY KEY,
    role_key VARCHAR(64) NOT NULL UNIQUE,
    privileges_json TEXT
);

-- 系统设置键值对
CREATE TABLE IF NOT EXISTS system_settings (
    "key" VARCHAR(128) PRIMARY KEY,
    value_json TEXT
);
