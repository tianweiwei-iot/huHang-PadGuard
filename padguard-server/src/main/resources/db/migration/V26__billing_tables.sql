-- 计费模块两张表补齐 Flyway。
-- 此前 subscriptions / payment_orders 仅由本地 H2 的 ddl-auto=update 隐式建表，
-- docker/postgres 形态（ddl-auto=none + Flyway）下缺失，导致仪表盘/订阅/下单接口
-- 报 relation "subscriptions" does not exist。列定义以 domain/BillingModels.kt 为唯一事实源。

CREATE TABLE IF NOT EXISTS subscriptions (
    id            VARCHAR(64)  NOT NULL PRIMARY KEY,
    user_id       VARCHAR(64)  NOT NULL,
    plan          VARCHAR(32)  NOT NULL,
    status        VARCHAR(32)  NOT NULL,
    device_limit  INT          NOT NULL DEFAULT 3,
    started_at    BIGINT       NOT NULL DEFAULT 0,
    expired_at    BIGINT       NOT NULL DEFAULT 0,
    auto_renew    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    BIGINT       NOT NULL DEFAULT 0,
    updated_at    BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_subscriptions_user ON subscriptions (user_id);

CREATE TABLE IF NOT EXISTS payment_orders (
    id              VARCHAR(64)  NOT NULL PRIMARY KEY,
    order_no        VARCHAR(64)  NOT NULL,
    user_id         VARCHAR(64)  NOT NULL,
    plan            VARCHAR(32)  NOT NULL,
    months          INT          NOT NULL DEFAULT 0,
    amount_fen      INT          NOT NULL DEFAULT 0,
    channel         VARCHAR(32)  NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    transaction_id  VARCHAR(128) NULL,
    created_at      BIGINT       NOT NULL DEFAULT 0,
    pay_deadline    BIGINT       NOT NULL DEFAULT 0,
    paid_at         BIGINT       NULL
);
-- order_no 实体标注 unique，且对账/回调去重依赖它
CREATE UNIQUE INDEX IF NOT EXISTS idx_orders_no ON payment_orders (order_no);
CREATE INDEX IF NOT EXISTS idx_orders_user ON payment_orders (user_id, created_at);
