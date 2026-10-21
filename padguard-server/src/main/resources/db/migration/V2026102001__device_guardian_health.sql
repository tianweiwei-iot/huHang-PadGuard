-- P3 守护健康度：孩子端随 15s 心跳上报守护进程快照，服务端持久化并据以判定 T3 守护失联。
-- 仅 docker / Postgres（ddl-auto=none + Flyway）环境执行；本地 H2 由 ddl-auto=update 自动加列。
-- IF NOT EXISTS 幂等：已上线环境不会重复加列，本地 H2 靠 ddl-auto 兜底，互不影响。
ALTER TABLE devices ADD COLUMN IF NOT EXISTS daemon_alive BOOLEAN DEFAULT FALSE;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS a11y_alive BOOLEAN DEFAULT FALSE;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS daemon_online_state VARCHAR(16);
ALTER TABLE devices ADD COLUMN IF NOT EXISTS last_restart_reason VARCHAR(32);
ALTER TABLE devices ADD COLUMN IF NOT EXISTS guard_permission_version INT DEFAULT 0;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS daemon_alive_at BIGINT;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS daemon_lost_alerted BOOLEAN DEFAULT FALSE;
