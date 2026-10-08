-- P4-5 审计日志：谁在何时对哪台设备做了什么（对标希沃校园集控）
-- 用途：合规追溯、责任界定、运营排查；家长端/校园端可查询与导出。
CREATE TABLE IF NOT EXISTS audit_logs (
    id          VARCHAR(64) PRIMARY KEY,
    user_id     VARCHAR(64),
    device_id   VARCHAR(64),
    action      VARCHAR(64) NOT NULL,
    target_type VARCHAR(32),
    target_id   VARCHAR(64),
    detail      TEXT,
    result      VARCHAR(16) NOT NULL DEFAULT 'SUCCESS',
    ip          VARCHAR(64),
    created_at  BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_audit_logs_user ON audit_logs (user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_audit_logs_device ON audit_logs (device_id, created_at);
