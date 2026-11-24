-- W2：离线指令补发（设备上线重放）所需的列与索引。
--
-- 背景：commands.expires_at 只有 5 分钟。设备长期离线时，家长在离线期间下发的
-- 管控指令会被 ExpiredCommandSweeper 直接收敛为 EXPIRED 终态，而设备上线后
-- 既没有补发队列也没有 retained 消息，这批策略变更永久丢失。
--
-- 本迁移只加列 + 加索引，不改变任何既有字段语义，也不回填存量数据：
-- 存量行 redelivery_deadline_at 保持 NULL，表示「不参与补发」(fail-closed)，
-- 避免功能上线的瞬间把历史积压的老指令（可能含数月前的解锁/采集）全量重放。
-- 补发行为全部由 padguard.command.* 配置控制，关闭开关即可完全退回原语义。

ALTER TABLE commands
    ADD COLUMN IF NOT EXISTS delivery_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS redelivery_deadline_at BIGINT,
    ADD COLUMN IF NOT EXISTS last_delivery_at BIGINT;

-- 补发查询的固定过滤条件：device_id + status + 仍在保留期内，按 created_at 做 FIFO。
-- 与实体 @Table(indexes) 中声明的同名索引对应：生产走 Flyway，本地 local profile
-- 走 hibernate ddl-auto=create-drop，两处都声明才能保证两种建表方式都拿到索引。
CREATE INDEX IF NOT EXISTS idx_commands_redelivery
    ON commands (device_id, status, redelivery_deadline_at, created_at);
