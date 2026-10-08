-- 局域网发现：记录孩子端的来源 IP 与最近活跃时刻。
--
-- 背景：家长端"局域网发现"此前是空的（服务端无该能力）。
-- 真实可行的做法不是让服务端去扫局域网（平板没有可被探测的监听端口），
-- 而是反过来记录：孩子端每次 HTTP 请求都会经过服务端，把来源 IP 记下来，
-- 家长端再来问"我这张网里有哪些平板"时就能答得出来。
--
-- 兼容 H2(MODE=PostgreSQL) 与 PostgreSQL；语句幂等。
ALTER TABLE devices ADD COLUMN IF NOT EXISTS last_ip VARCHAR(64);
ALTER TABLE devices ADD COLUMN IF NOT EXISTS last_active_at BIGINT;

-- 存量行兜底：从未记录过 IP 的设备视为"不在任何网络中"，不影响既有功能。
UPDATE devices SET last_active_at = 0 WHERE last_active_at IS NULL;
