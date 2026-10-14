-- 接入会话（Enrollment Session）：替换一次性绑定码 bind_codes。
--
-- 为什么必须有这张表：
-- 旧模型里"绑定码"是一个孤立的 6 位随机串，它身上同时挂着三件互相冲突的职责 ——
-- 家长端二维码的内容、家长端配对码的展示值、以及绑定结果的回写位置。
-- 三者共用一个没有身份、没有生命周期的对象，必然导致：
--   1) 家长端要展示二维码和配对码时只能各生成一次（两次请求 = 两个不同的码）；
--   2) 绑定结果只能硬塞回码上（markBound），码既是凭证又是状态容器；
--   3) 家长端无法订阅"这次接入"的进展，只能拿着码反复轮询。
--
-- 新模型把"一次接入"提升为有身份的实体：
--   - id：会话身份，家长端订阅它、服务端推送它，二维码与配对码都只是它的呈现形式；
--   - pairing_code：孩子端输入/扫码用的 6 位短码（对外契约不变，孩子端无需改动）；
--   - state：PENDING / BOUND / EXPIRED / CANCELLED，由服务端权威判定；
--   - device_id：绑定结果归属的位置，不再污染凭证本身。
CREATE TABLE enrollment_sessions (
    id           VARCHAR(64) PRIMARY KEY,
    user_id      VARCHAR(64) NOT NULL REFERENCES users (id),
    pairing_code VARCHAR(8)  NOT NULL UNIQUE,
    state        VARCHAR(16) NOT NULL,
    device_id    VARCHAR(64),
    expires_at   BIGINT      NOT NULL,
    bound_at     BIGINT,
    created_at   BIGINT      NOT NULL
);

-- 「某家长当前有没有进行中的接入会话」是最高频查询（家长端每次打开接入页都问一次）
CREATE INDEX idx_enroll_user_state ON enrollment_sessions (user_id, state);
-- 过期扫描：会话过期是时间驱动的，按 expires_at 收拢避免全表扫
CREATE INDEX idx_enroll_expires ON enrollment_sessions (expires_at);

-- 旧表整体退役：绑定码是 10 分钟即失效的临时数据，无迁移价值；
-- 保留双轨只会让"到底以哪个为准"再次变成悬案。
DROP TABLE IF EXISTS bind_codes;
