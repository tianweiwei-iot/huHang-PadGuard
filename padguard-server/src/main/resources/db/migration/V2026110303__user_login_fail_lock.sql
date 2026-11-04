-- 登录失败计数落库
--
-- 背景：AuthService 里的 failedAttempts / lockedUntil 原本是两个 ConcurrentHashMap。
-- 内存态在这个项目上有两个真实短板：
--   ① 重启即清零。攻击者不需要"攻破"，只要等一次发布（或诱发一次 OOM 重启）
--      就能把已累计的失败次数抹掉，等于免费续一轮重试额度。
--   ② 多实例各算各的。max-fail-attempts=5 在 3 个实例负载均衡下实际接近 15 次，
--      而且同一账号的请求被分到哪个实例是随机的，计数增长连"稳定"都谈不上。
--
-- 落库后由单条 UPDATE 完成"自增 或 达阈值则加锁并清零"，靠数据库行锁保证原子性，
-- 与实例数、重启次数都无关。
--
-- login_locked_until 用 BIGINT 存毫秒时间戳而不是 TIMESTAMP：项目里所有时间列
-- （created_at / last_online_at / token 的 exp）都是 BIGINT 毫秒，保持一致可以
-- 避免"这一列要不要做时区换算"的判断负担 —— 时间戳本身不含时区。

ALTER TABLE users ADD COLUMN IF NOT EXISTS login_fail_count INT DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS login_locked_until BIGINT;

-- 存量行回填：NULL 会导致 `login_fail_count + 1` 在 SQL 里结果为 NULL，
-- 于是条件 CASE 永远不成立、阈值判断彻底失效 —— 计数看着"永远是 0"，锁定永不触发。
UPDATE users SET login_fail_count = 0 WHERE login_fail_count IS NULL;

-- 登录路径按 phone 命中（phone 上已有唯一索引），这两个列只随该行读写，不必单独建索引。
