-- 家长端「开始计时」工单表（每日计时须孩子确认后才开始）。
-- 本地 H2 由 ddl-auto=update 自动建表；此迁移供 docker/postgres 等外部数据库使用。
-- 状态机：PENDING（已发起，等待孩子确认）→ CONFIRMED（孩子已确认）或 EXPIRED（跨日未确认作废）。
CREATE TABLE IF NOT EXISTS usage_start_requests (
    request_id        VARCHAR(64)  NOT NULL PRIMARY KEY,
    device_id         VARCHAR(64)  NOT NULL,
    day_key           VARCHAR(20)  NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    source            VARCHAR(16)  NOT NULL,
    created_at        BIGINT       NOT NULL DEFAULT 0,
    confirmed_at      BIGINT       NULL
);

CREATE INDEX IF NOT EXISTS idx_usage_start_device_day ON usage_start_requests(device_id, day_key);
