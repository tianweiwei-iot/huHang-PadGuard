-- P2 内容管控补强：网址分类库 + 敏感词库 + 设备级上网过滤开关
-- 说明：H2（local/home profile）由 ddl-auto=update 自动加列/建表；此处仅覆盖 PostgreSQL（docker profile）。
-- 字典种子数据由 DataInitializer 以幂等方式统一播种（postgres 与 h2 双覆盖）。

-- 设备级上网过滤开关：安全搜索 + 按分类屏蔽
ALTER TABLE device_settings ADD COLUMN IF NOT EXISTS web_safe_search BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE device_settings ADD COLUMN IF NOT EXISTS web_blocked_categories TEXT;

-- 网址分类库：本地库 + 云端分类 API 的落库缓存（黄/赌/毒/暴力/游戏/社交…）
CREATE TABLE IF NOT EXISTS url_categories (
    id                 VARCHAR(64) PRIMARY KEY,
    name               VARCHAR(64) NOT NULL,
    description        TEXT,
    risk_level         VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',  -- LOW / MEDIUM / HIGH
    blocked_by_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at         BIGINT NOT NULL
);

-- 敏感词库：文本鉴黄/涉赌/暴力等本地词典（云端 AI 识别的本地兜底）
CREATE TABLE IF NOT EXISTS sensitive_words (
    id         VARCHAR(64) PRIMARY KEY,
    word       VARCHAR(128) NOT NULL,
    category   VARCHAR(32) NOT NULL DEFAULT 'PORN',  -- PORN / GAMBLING / VIOLENCE / FRAUD / OTHER
    level      VARCHAR(16) NOT NULL DEFAULT 'HIGH',  -- LOW / MEDIUM / HIGH
    created_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sensitive_words_word ON sensitive_words (word);
