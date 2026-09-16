-- PadGuard 服务端扩展表结构 (V2)
-- 覆盖：设备分组、截图、定位轨迹、告警、信息发布、电子围栏、
--       临时解锁工单、应用策略、时段限制、设备设置、策略模板、媒体任务、上传文件

CREATE TABLE device_groups (
    id           VARCHAR(64) PRIMARY KEY,
    name         VARCHAR(64) NOT NULL,
    scene_type   VARCHAR(20) NOT NULL,
    owner_user_id VARCHAR(64) REFERENCES users (id),
    created_at   BIGINT      NOT NULL
);
CREATE INDEX idx_device_groups_owner ON device_groups (owner_user_id);

CREATE TABLE uploaded_files (
    id           VARCHAR(64) PRIMARY KEY,
    content_type VARCHAR(64) NOT NULL,
    stored_path  VARCHAR(512) NOT NULL,
    size         BIGINT      NOT NULL,
    created_at   BIGINT      NOT NULL
);

CREATE TABLE screenshots (
    id           VARCHAR(64) PRIMARY KEY,
    device_id    VARCHAR(64) NOT NULL REFERENCES devices (id),
    shot_id      VARCHAR(64),
    trigger_type VARCHAR(16),
    file_url     VARCHAR(512),
    thumbnail_url VARCHAR(512),
    width        INT,
    height       INT,
    captured_at  BIGINT,
    received_at  BIGINT      NOT NULL,
    status       VARCHAR(16) NOT NULL DEFAULT 'READY'
);
CREATE INDEX idx_screenshots_device_id ON screenshots (device_id, captured_at);

CREATE TABLE location_tracks (
    id           VARCHAR(64) PRIMARY KEY,
    device_id    VARCHAR(64) NOT NULL REFERENCES devices (id),
    lat          DOUBLE PRECISION,
    lng          DOUBLE PRECISION,
    accuracy     DOUBLE PRECISION,
    provider     VARCHAR(20),
    ts           BIGINT,
    received_at  BIGINT      NOT NULL
);
CREATE INDEX idx_location_tracks_device_id ON location_tracks (device_id, ts);

CREATE TABLE alerts (
    id           VARCHAR(64) PRIMARY KEY,
    device_id    VARCHAR(64) NOT NULL REFERENCES devices (id),
    user_id      VARCHAR(64) REFERENCES users (id),
    level        VARCHAR(16) NOT NULL,
    title        VARCHAR(128) NOT NULL,
    message      TEXT,
    category     VARCHAR(32),
    status       VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    detail_json  TEXT,
    triggered_at BIGINT      NOT NULL,
    acknowledged_at BIGINT,
    resolved_at  BIGINT
);
CREATE INDEX idx_alerts_device_id ON alerts (device_id);
CREATE INDEX idx_alerts_user_status ON alerts (user_id, status);

CREATE TABLE published_messages (
    id           VARCHAR(64) PRIMARY KEY,
    device_id    VARCHAR(64) NOT NULL REFERENCES devices (id),
    content_type VARCHAR(16) NOT NULL,
    text         TEXT,
    media_url    VARCHAR(512),
    media_name   VARCHAR(255),
    display_seconds INT,
    full_screen  BOOLEAN,
    play_audio   BOOLEAN,
    published_at BIGINT      NOT NULL
);
CREATE INDEX idx_published_messages_device_id ON published_messages (device_id, published_at);

CREATE TABLE geofences (
    device_id     VARCHAR(64) PRIMARY KEY REFERENCES devices (id),
    enabled       BOOLEAN NOT NULL DEFAULT FALSE,
    name          VARCHAR(64),
    center_lat    DOUBLE PRECISION,
    center_lng    DOUBLE PRECISION,
    radius_meters INT,
    alert_on_exit BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE unlock_tickets (
    id            VARCHAR(64) PRIMARY KEY,
    device_id     VARCHAR(64) NOT NULL REFERENCES devices (id),
    user_id       VARCHAR(64) REFERENCES users (id),
    package_name  VARCHAR(128),
    app_label     VARCHAR(128),
    duration_minutes INT,
    reason        TEXT,
    status        VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at    BIGINT      NOT NULL,
    resolved_at   BIGINT
);
CREATE INDEX idx_unlock_tickets_device_id ON unlock_tickets (device_id, status);

CREATE TABLE app_policies (
    id               VARCHAR(64) PRIMARY KEY,
    device_id        VARCHAR(64) NOT NULL REFERENCES devices (id),
    package_name     VARCHAR(128) NOT NULL,
    app_name         VARCHAR(128),
    is_blocked       BOOLEAN NOT NULL DEFAULT FALSE,
    daily_limit_minutes INT
);
CREATE INDEX idx_app_policies_device_id ON app_policies (device_id);

CREATE TABLE time_restrictions (
    id           VARCHAR(64) PRIMARY KEY,
    device_id    VARCHAR(64) NOT NULL REFERENCES devices (id),
    day_of_week  INT         NOT NULL,
    start_time   VARCHAR(8)  NOT NULL,
    end_time     VARCHAR(8)  NOT NULL,
    max_minutes  INT,
    is_enabled   BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX idx_time_restrictions_device_id ON time_restrictions (device_id);

CREATE TABLE device_settings (
    device_id              VARCHAR(64) PRIMARY KEY REFERENCES devices (id),
    auto_refresh_seconds   INT         NOT NULL DEFAULT 15,
    high_definition        BOOLEAN     NOT NULL DEFAULT FALSE,
    record_resolution      VARCHAR(16) NOT NULL DEFAULT 'HD_720P',
    record_with_audio      BOOLEAN     NOT NULL DEFAULT TRUE,
    allow_remote_lock      BOOLEAN     NOT NULL DEFAULT TRUE,
    browser_disabled       BOOLEAN     NOT NULL DEFAULT FALSE,
    smart_shutdown_enabled BOOLEAN     NOT NULL DEFAULT FALSE,
    smart_shutdown_start   VARCHAR(8),
    smart_shutdown_end     VARCHAR(8),
    daily_limit_minutes    INT         NOT NULL DEFAULT 120,
    web_blocked_urls       TEXT
);

CREATE TABLE policy_templates (
    id           VARCHAR(64) PRIMARY KEY,
    name         VARCHAR(64) NOT NULL,
    description  TEXT,
    scene_type   VARCHAR(20) NOT NULL,
    category     VARCHAR(32),
    package_json TEXT
);

CREATE TABLE media_tasks (
    id           VARCHAR(64) PRIMARY KEY,
    device_id    VARCHAR(64) NOT NULL REFERENCES devices (id),
    kind         VARCHAR(16) NOT NULL,
    status       VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    url          VARCHAR(512),
    mime_type    VARCHAR(64),
    size         BIGINT,
    duration_seconds INT,
    started_at   BIGINT      NOT NULL,
    finished_at  BIGINT
);
CREATE INDEX idx_media_tasks_device_id ON media_tasks (device_id, kind);

-- 预置策略模板
INSERT INTO policy_templates (id, name, description, scene_type, category, package_json) VALUES
('tpl_family_basic', '家庭基础防护', '适合家庭场景的均衡管控：白名单应用、夜间锁屏、护眼提醒', 'FAMILY', 'BASIC',
 '{"peripheral":{"wifi":"ALLOW","bluetooth":"DISABLE","camera":"DISABLE","usbFileTransfer":"DISABLE"},
   "app":{"mode":"WHITELIST","whitelist":["com.android.calculator2","com.example.study"],"blacklist":["com.tencent.mm","com.ss.android.ugc.aweme"]},
   "appLimit":{"dailyTotalMinutes":120},
   "schedule":{"timezone":"Asia/Shanghai","rules":[{"dayTypes":["WEEKDAY"],"start":"21:30","end":"07:00","action":"LOCK"}]},
   "eyeCare":{"enabled":true,"continuousMinutes":40,"restMinutes":10},
   "monitoring":{"heartbeatIntervalSec":30,"logUploadIntervalSec":300}}'),
('tpl_school_focus', '课堂专注模式', '适合学校场景：仅学习类应用可用、上课时段全锁、禁用相机与浏览器下载', 'SCHOOL', 'FOCUS',
 '{"peripheral":{"wifi":"ALLOW","bluetooth":"DISABLE","camera":"DISABLE","microphone":"DISABLE","castScreen":"DISABLE"},
   "app":{"mode":"WHITELIST","whitelist":["com.example.study","com.android.calculator2"],"blacklist":["com.tencent.mm"]},
   "appLimit":{"dailyTotalMinutes":240},
   "schedule":{"timezone":"Asia/Shanghai","rules":[{"dayTypes":["WEEKDAY"],"start":"08:00","end":"17:00","action":"LOCK"}]},
   "kiosk":{"enabled":true,"mode":"MULTI_APP","allowedPackages":["com.example.study"]},
   "security":{"antiUninstall":true,"blockRoot":true}}');
