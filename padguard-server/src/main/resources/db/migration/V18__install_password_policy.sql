-- 安装 / 卸载确认页是否需要管理密码（非 DO 设备无障碍软拦截闸门）
-- 默认都要密码（核心安全能力），家长可在「应用管理」页关闭。
-- IF NOT EXISTS 幂等：docker/default profile 已上线时不会重复加列，local profile 由 ddl-auto 兜底。
ALTER TABLE device_settings ADD COLUMN IF NOT EXISTS require_password_on_install BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE device_settings ADD COLUMN IF NOT EXISTS require_password_on_uninstall BOOLEAN NOT NULL DEFAULT TRUE;
