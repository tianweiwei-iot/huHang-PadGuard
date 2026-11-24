-- 第二步：策略版本回滚 / 定时生效所必需的两列 + 历史查询索引。
--
-- 背景：policies 表本来就会为每个版本追加一行（PolicyVersions.allocate 取 max+1），
-- 但一直缺少两个元数据：
--   1) source —— 无法区分「子表重建的常规策略」与「版本回滚的历史快照」。
--      回滚包按定义与当前子表不一致，会被 ensureRebuilt 在设备拉策略时判为 drift
--      并立刻重建覆盖，导致回滚成功但设备没变。
--   2) rollback_of —— 无法在版本列表里说明这一行是从哪个版本回滚来的。
--
-- 存量行一律按 NORMAL 填充：上线瞬间没有任何行处于回滚态，不会出现
-- 「服务重启后全量设备的策略自愈被莫名跳过」的 fail-open。
-- effective_from / effective_to 在 V1 已存在，本迁移不改动其语义。

ALTER TABLE policies
    ADD COLUMN IF NOT EXISTS source VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
    ADD COLUMN IF NOT EXISTS rollback_of INT;

-- 版本倒序列表（GET /v1/policies/{deviceId}/versions）与按版本精确定位（回滚目标）。
-- 实体上也声明了同名索引，供 local/home profile 的 ddl-auto=update 路径使用。
CREATE INDEX IF NOT EXISTS idx_policies_device_version
    ON policies (device_id, version DESC);
