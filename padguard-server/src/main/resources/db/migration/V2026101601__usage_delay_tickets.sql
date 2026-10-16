-- 延时审批工单表（根因修复：把"孩子申请延时"与"真正延长额度"解耦）
-- 旧实现孩子的延时申请在提交瞬间就直接累加 extendedMinutes 并即时生效（"孩子自己批准自己"），
-- 家长端既无审批入口也无法同步。本表引入独立工单，PENDING 待家长审批，APPROVED 才真正延长。
CREATE TABLE usage_delay_tickets (
    id           VARCHAR(64)  PRIMARY KEY,
    device_id    VARCHAR(64)  NOT NULL,
    user_id      VARCHAR(64),
    minutes      INT          NOT NULL,
    reason       TEXT,
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at   BIGINT       NOT NULL,
    resolved_at  BIGINT
);

CREATE INDEX idx_usage_delay_tickets_device ON usage_delay_tickets (device_id, status);
CREATE INDEX idx_usage_delay_tickets_user   ON usage_delay_tickets (user_id, status);
