-- PadGuard 服务端初始化表结构 (V1)
-- 命名约定：实体字段 camelCase 经 Spring 默认 physical naming 映射为 snake_case

CREATE TABLE users (
    id           VARCHAR(64) PRIMARY KEY,
    phone        VARCHAR(20)  NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    nickname     VARCHAR(64),
    avatar       VARCHAR(255),
    role         VARCHAR(20)  NOT NULL,
    scene_type   VARCHAR(20)  NOT NULL,
    created_at   BIGINT       NOT NULL
);

CREATE TABLE devices (
    id              VARCHAR(64) PRIMARY KEY,
    user_id         VARCHAR(64) REFERENCES users (id),
    name            VARCHAR(64),
    device_sn       VARCHAR(64),
    fingerprint     VARCHAR(128),
    model           VARCHAR(64),
    brand           VARCHAR(64),
    os_version      VARCHAR(20),
    sdk_int         INT,
    app_version     VARCHAR(20),
    control_mode    VARCHAR(20),
    scene_mode      VARCHAR(20),
    scene           VARCHAR(20),
    online_status   VARCHAR(20)  NOT NULL DEFAULT 'OFFLINE',
    last_online_at  BIGINT,
    battery_level   INT,
    latitude        DOUBLE PRECISION,
    longitude       DOUBLE PRECISION,
    group_id        VARCHAR(64),
    policy_version  INT         NOT NULL DEFAULT 0,
    hmac_secret     VARCHAR(128) NOT NULL,
    device_token_hash VARCHAR(128) NOT NULL,
    mqtt_password   VARCHAR(64)  NOT NULL,
    created_at      BIGINT       NOT NULL
);
CREATE INDEX idx_devices_user_id ON devices (user_id);

CREATE TABLE bind_codes (
    code        VARCHAR(8) PRIMARY KEY,
    user_id     VARCHAR(64) NOT NULL REFERENCES users (id),
    device_id   VARCHAR(64),
    expires_at  BIGINT      NOT NULL,
    used        BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at  BIGINT      NOT NULL
);
CREATE INDEX idx_bind_codes_user_id ON bind_codes (user_id);

CREATE TABLE policies (
    id           VARCHAR(64) PRIMARY KEY,
    device_id    VARCHAR(64) NOT NULL REFERENCES devices (id),
    version      INT         NOT NULL,
    package_json TEXT,
    scene        VARCHAR(20),
    effective_from BIGINT,
    effective_to   BIGINT,
    updated_at   BIGINT      NOT NULL
);
CREATE INDEX idx_policies_device_id ON policies (device_id);

CREATE TABLE commands (
    id          VARCHAR(64) PRIMARY KEY,
    device_id   VARCHAR(64) NOT NULL REFERENCES devices (id),
    msg_id      VARCHAR(64) NOT NULL UNIQUE,
    type        VARCHAR(32) NOT NULL,
    priority    VARCHAR(16),
    payload_json TEXT,
    signature   VARCHAR(128),
    status      VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    expires_at  BIGINT      NOT NULL,
    created_at  BIGINT      NOT NULL,
    executed_at BIGINT
);
CREATE INDEX idx_commands_device_id ON commands (device_id);

CREATE TABLE usage_logs (
    id          VARCHAR(64) PRIMARY KEY,
    device_id   VARCHAR(64) NOT NULL REFERENCES devices (id),
    log_id      VARCHAR(64) NOT NULL UNIQUE,
    type        VARCHAR(32),
    timestamp   BIGINT,
    payload_json TEXT,
    received_at BIGINT      NOT NULL
);
CREATE INDEX idx_usage_logs_device_id ON usage_logs (device_id);

CREATE TABLE device_events (
    id          VARCHAR(64) PRIMARY KEY,
    device_id   VARCHAR(64) NOT NULL REFERENCES devices (id),
    event_id    VARCHAR(64) NOT NULL UNIQUE,
    type        VARCHAR(32),
    level       VARCHAR(16),
    detail_json TEXT,
    timestamp   BIGINT
);
CREATE INDEX idx_device_events_device_id ON device_events (device_id);
