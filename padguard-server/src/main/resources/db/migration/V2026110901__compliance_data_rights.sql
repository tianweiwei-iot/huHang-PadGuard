-- 第四步：数据主体权利（撤回授权 / 数据导出 / 账号注销 / 申诉）+ 审计日志防篡改哈希。
--
-- 依据：《个人信息保护法》查阅复制/更正删除/撤回同意/注销、《未成年人网络保护条例》监护人同意与投诉举报渠道。
-- 本迁移只加列 + 建表 + 索引，不改变既有字段语义，存量行按 fail-closed 兜底：
--   devices.consent_revoked 默认 false（未撤回）；
--   audit_logs.hash 默认 NULL（历史日志不参与链校验，verifyIntegrity 仅校验有 hash 的行）；
--   appeals / account_deletion_requests 为新表，上线即空。
-- 全部 DDL 用 IF NOT EXISTS / DEFAULT，与 H2(MODE=PostgreSQL) 与 PG16 双兼容。

-- 1) 设备级「授权撤回」标记：撤回后服务端停止落库个人数据，并作为孩子端本地停采信号源。
ALTER TABLE devices
    ADD COLUMN IF NOT EXISTS consent_revoked BOOLEAN NOT NULL DEFAULT FALSE;

-- 2) 审计日志防篡改：逐行 SHA-256 哈希（PRD M14-3「至少具备哈希链 / 只写隔离之一」）。
--    任何字段被篡改都会导致重算 hash 不一致；verifyIntegrity() 可批量校验。
ALTER TABLE audit_logs
    ADD COLUMN IF NOT EXISTS hash VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_audit_logs_hash
    ON audit_logs (hash);

-- 3) 申诉工单：未成年人管控场景下，家长/监护人对管控动作提出异议的受理通道（合规 P0）。
CREATE TABLE IF NOT EXISTS appeals (
    id          VARCHAR(40) PRIMARY KEY,
    user_id     VARCHAR(40) NOT NULL,
    device_id   VARCHAR(40),
    subject     VARCHAR(60) NOT NULL,
    detail      TEXT,
    contact     VARCHAR(120),
    status      VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_at  BIGINT NOT NULL,
    updated_at  BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_appeals_user   ON appeals (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_appeals_status ON appeals (status, created_at DESC);

-- 4) 账号注销请求：冷静期 + 凭证复核，到期/确认后级联清除全部个人数据。
CREATE TABLE IF NOT EXISTS account_deletion_requests (
    id           VARCHAR(40) PRIMARY KEY,
    user_id      VARCHAR(40) NOT NULL,
    ticket       VARCHAR(64) NOT NULL,
    reason       VARCHAR(200),
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    requested_at BIGINT NOT NULL,
    execute_at   BIGINT NOT NULL,
    cancelled    BOOLEAN NOT NULL DEFAULT FALSE,
    cancelled_at BIGINT
);
CREATE INDEX IF NOT EXISTS idx_deletion_user   ON account_deletion_requests (user_id, status);
CREATE UNIQUE INDEX IF NOT EXISTS idx_deletion_ticket ON account_deletion_requests (ticket);
