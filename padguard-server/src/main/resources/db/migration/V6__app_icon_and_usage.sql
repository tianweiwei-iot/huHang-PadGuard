-- 应用图标 + 使用明细：让家长端"今日使用情况"能显示真实图标与起止时间
--
-- 背景：此前 installed_apps 只存名称与版本，家长端列表里全是文字。
-- 家长要在一堆包名/应用名里辨认"孩子刚才用的到底是哪个"，只能靠猜；
-- 使用时长也只有一个总数，看不出"晚上几点还在用"。

ALTER TABLE installed_apps ADD COLUMN IF NOT EXISTS icon_url  VARCHAR(512);
ALTER TABLE installed_apps ADD COLUMN IF NOT EXISTS icon_hash VARCHAR(64);

-- icon_hash 是"图标是否变化"的判断依据：
-- 孩子端每 30 分钟全量上报一次，若每次都无条件重新落盘，
-- 一台设备上百个图标就会产生上百次无谓的磁盘写入与历史文件堆积。
-- 服务端只在哈希变化时覆盖文件，其余情况沿用既有 icon_url。
CREATE INDEX IF NOT EXISTS idx_installed_apps_icon_hash ON installed_apps (device_id, package_name, icon_hash);
