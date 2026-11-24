-- 不满十四周岁未成年人个人信息处理：监护人单独同意留痕（PIPL 第 29/31 条）。
-- 注意：本表记录的是「家长申报孩子出生日期 + 勾选单独同意」的证据，不是实名年龄核验结果；
-- 法务若要求接入公安/第三天实名核验，应另走增值通道，不得把本表当作已核验凭证。

CREATE TABLE IF NOT EXISTS guardian_consents (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    child_birth_date VARCHAR(20) NOT NULL,
    consent BOOLEAN NOT NULL,
    consent_text_version VARCHAR(40) NOT NULL,
    created_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_guardian_consents_user_device
    ON guardian_consents (user_id, device_id);
