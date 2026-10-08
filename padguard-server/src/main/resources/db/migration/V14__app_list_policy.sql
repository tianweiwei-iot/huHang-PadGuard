-- 应用管控（无 DO 降级）落地：名单模式 + 启动密码开关。
--
-- 背景：家长端一直有"应用管控"配置，但
--   1) PolicyExtensionService.rebuildPolicy 把 app.mode 硬编码成 BLACKLIST，
--      家长选白名单也下发不出去；
--   2) 没有 Device Owner 时 setApplicationHidden / setPackagesSuspended 全部 UNSUPPORTED，
--      配置形同虚设。
-- 本次补齐这两块的数据落点：模式可配置，启动密码开关可配置，
-- 孩子端据此由无障碍服务做"安装/卸载确认页 +（可选）受管控应用启动"的密码拦截。
--
-- 兼容 H2(MODE=PostgreSQL) 与 PostgreSQL；语句幂等。
ALTER TABLE device_settings ADD COLUMN IF NOT EXISTS app_list_mode VARCHAR(20);
ALTER TABLE device_settings ADD COLUMN IF NOT EXISTS require_password_on_launch BOOLEAN;

-- 存量行兜底：NULL 视为黑名单 / 不要求启动密码，保持既有行为不变。
-- （服务层对 null 也有兜底，这里只是让数据本身自洽，便于排查。）
UPDATE device_settings SET app_list_mode = 'BLACKLIST' WHERE app_list_mode IS NULL;
UPDATE device_settings SET require_password_on_launch = FALSE WHERE require_password_on_launch IS NULL;
