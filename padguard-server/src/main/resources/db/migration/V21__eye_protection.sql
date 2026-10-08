-- 护眼模式：开关 + 滤镜强度。
--
-- 背景：家长端"护眼"开关一直存在，但服务端既没有存储也没有下发通道，
-- 只能在 onclick 里回一句"服务端未提供护眼参数接口" —— 家长一拨开关就报错。
-- 更关键的是孩子端从未实现过任何护眼效果，就算补了存储也只是"开关不报错"的假功能，
-- 因此本次连同孩子端的悬浮滤镜一起落地（见 EyeProtectionController）。
--
-- 兼容 H2(MODE=PostgreSQL) 与 PostgreSQL；语句幂等。
ALTER TABLE device_settings ADD COLUMN IF NOT EXISTS eye_protection_enabled BOOLEAN;
ALTER TABLE device_settings ADD COLUMN IF NOT EXISTS eye_protection_level INTEGER;

-- 存量行兜底：默认关闭、强度取中间值 3，与实体默认值保持一致（避免发出 NULL 让孩子端无从解释）。
UPDATE device_settings SET eye_protection_enabled = FALSE WHERE eye_protection_enabled IS NULL;
UPDATE device_settings SET eye_protection_level = 3 WHERE eye_protection_level IS NULL;
