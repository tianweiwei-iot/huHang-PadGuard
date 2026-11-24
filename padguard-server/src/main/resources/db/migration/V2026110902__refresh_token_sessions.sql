-- 第三步（安全）：refresh token 单设备/单会话吊销 + 轮换。
--
-- 之前 refresh token 是无状态 JWT，仅有 users.token_version 这一种「整账号吊销」手段：
-- 任一台设备的 refresh token 泄漏（30 天有效期），只能靠登出/改密把该账号全部设备一起踢下线，
-- 无法「只吊销这一台」。本迁移引入 user_sessions 服务端状态，使 refresh 具备：
--   1) 轮换：每次 refresh 生成新 jti，旧 refresh token 立即失效（防重放/防多端共用同一令牌）；
--   2) 单设备吊销：家长可在「登录设备」列表里单独踢掉某一台，不影响其他设备；
--   3) 全局吊销：登出/改密/后台动作仍保留 token_version 整账号吊销作为兜底。
-- 全部 DDL 用 IF NOT EXISTS，H2(MODE=PostgreSQL) 与 PG16 双兼容。

CREATE TABLE IF NOT EXISTS user_sessions (
    id               VARCHAR(40)  PRIMARY KEY,
    user_id          VARCHAR(40)  NOT NULL,
    device_label     VARCHAR(80),
    refresh_jti      VARCHAR(64)  NOT NULL,
    created_at       BIGINT       NOT NULL,
    last_refreshed_at BIGINT      NOT NULL,
    revoked          BOOLEAN      NOT NULL DEFAULT FALSE,
    revoked_at       BIGINT
);

CREATE INDEX IF NOT EXISTS idx_user_sessions_user      ON user_sessions (user_id, revoked);
CREATE INDEX IF NOT EXISTS idx_user_sessions_jti       ON user_sessions (refresh_jti);
CREATE UNIQUE INDEX IF NOT EXISTS idx_user_sessions_jti_uniq ON user_sessions (refresh_jti);
