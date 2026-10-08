-- 性能补索引：查询路径上的复合索引
--
-- 为什么必须有这份迁移（即使实体上已经用 @Index 声明过）：
--   实体上的 @Index 只对 Hibernate 建表（ddl-auto）生效。生产 / docker 环境用的是
--   ddl-auto=none + Flyway，索引只能由迁移脚本创建。两边必须同时覆盖，
--   否则一边补了另一边还是全表扫描，同一份代码在不同环境下性能表现不一致，
--   排查时极易误判为"代码没问题、是机器慢"。
--
-- 全部使用 IF NOT EXISTS：索引在存量库上可能已由早期脚本或 ddl-auto 建出，
-- 重复执行迁移不应失败。

-- 指令轮询下发：孩子端每 3 秒一次的高频查询。
-- 覆盖完整过滤条件 device_id + status + expires_at + created_at，
-- 让「取待执行指令」走索引定位，而不是捞出该设备全部历史指令再在内存里过滤。
CREATE INDEX IF NOT EXISTS idx_commands_poll
    ON commands (device_id, status, expires_at, created_at);

-- 使用日志：数据量最大的表。统计看板按「设备 + 时间范围」扫描，
-- 另按「设备 + 类型」聚合（APP_USAGE / USAGE_TOTAL / URL_VISIT）。
CREATE INDEX IF NOT EXISTS idx_usage_logs_device_ts
    ON usage_logs (device_id, timestamp);

CREATE INDEX IF NOT EXISTS idx_usage_logs_device_type
    ON usage_logs (device_id, type, timestamp);

-- 设备事件（违规统计）按「设备 + 时间范围」扫描
CREATE INDEX IF NOT EXISTS idx_device_events_device_ts
    ON device_events (device_id, timestamp);

-- 告警列表按「用户 + 设备 + 状态」过滤：
-- 原先只能查 userId 后在内存 filter deviceId，分页结果还会因此偏少。
CREATE INDEX IF NOT EXISTS idx_alerts_user_device_status
    ON alerts (user_id, device_id, status);

-- 远程截图回执按 (device_id, shot_id) 精确回查
CREATE INDEX IF NOT EXISTS idx_screenshots_device_shot
    ON screenshots (device_id, shot_id);
