-- 平板使用计时会话表（三端时间一致：计时起点与额度由服务端锚定）
-- 本地 H2 由 ddl-auto=update 自动建表；此迁移供 docker/postgres 等外部数据库使用。
CREATE TABLE IF NOT EXISTS usage_sessions (
    device_id          VARCHAR(64)  NOT NULL PRIMARY KEY,
    day_key            VARCHAR(20)  NOT NULL,
    start_epoch        BIGINT       NULL,
    quota_minutes      INT          NOT NULL DEFAULT 0,
    extended_minutes   INT          NOT NULL DEFAULT 0,
    last_delay_epoch   BIGINT       NULL,
    created_at         BIGINT       NOT NULL DEFAULT 0,
    updated_at         BIGINT       NOT NULL DEFAULT 0
);
