-- ============================================================================
-- subscriptions.user_id 重复诊断与去重（上线前必读）
--
-- 背景：实体上已声明 user_id 唯一约束（BillingModels.Subscription），
--   - 生产（PG）走 Flyway：V2026110101__subscription_user_unique.sql 会建唯一索引；
--   - 家庭/单机部署（H2 文件库）走 ddl-auto=update：Hibernate 启动时补唯一约束。
--
-- 两条路径都要求存量数据**没有重复**。若存在重复，加约束会直接失败 → 服务起不来。
-- 这是刻意的 fail-fast：带着重复数据的库，所有 findByUserId 单条查询都会抛
-- IncorrectResultSizeDataAccessException，该家长的全部接口永久 500，且无自愈路径。
--
-- 用法：先跑第 1 段看有没有重复；有重复再按第 2 段人工确认后清理，最后重启服务。
-- ============================================================================

-- ---------------------------------------------------------------- 1. 诊断
-- 列出所有"同一家长存在多条订阅"的情况，按重复条数倒序。
-- 预期结果：0 行。有行就必须先处理，不要跳过。
SELECT user_id,
       COUNT(*)          AS dup_count,
       MIN(expired_at)   AS earliest_expire,
       MAX(expired_at)   AS latest_expire,
       MIN(created_at)   AS first_created
FROM subscriptions
GROUP BY user_id
HAVING COUNT(*) > 1
ORDER BY dup_count DESC, latest_expire DESC;

-- 逐条明细（把 :dup_user_id 换成上面查出的 user_id，确认哪条是"应该保留"的）
-- SELECT id, user_id, plan, status, device_limit, started_at, expired_at, created_at, updated_at
-- FROM subscriptions
-- WHERE user_id = :dup_user_id
-- ORDER BY expired_at DESC;

-- ---------------------------------------------------------------- 2. 去重
-- 保留口径：**expired_at 最大**的那条（家长已付费的时间不能丢）。
-- 若两条到期时间相同，再按 updated_at 倒序。
--
-- 下面的语句只"选出应删除的行"，不直接删 —— 先看清楚再执行 DELETE。
-- 注意：删除前确认该 user 的 payment_orders 仍在（订单是账，订阅是权益凭证），
--      删掉的订阅若对应某笔已支付订单，应人工核对到期时间是否已体现在保留行上。
--
-- SELECT s.id, s.user_id, s.plan, s.status, s.expired_at, s.updated_at
-- FROM subscriptions s
-- JOIN (
--     SELECT user_id, MAX(expired_at) AS keep_expire
--     FROM subscriptions
--     GROUP BY user_id
--     HAVING COUNT(*) > 1
-- ) d ON d.user_id = s.user_id
-- WHERE s.expired_at < d.keep_expire
-- ORDER BY s.user_id, s.expired_at;

-- 确认无误后逐条删除（**不要**用 user_id 全删，那会把保留行也删掉）：
-- DELETE FROM subscriptions WHERE id = '<上面查出的 id>';

-- ---------------------------------------------------------------- 3. 验证
-- 重跑第 1 段应为 0 行；随后重启服务，确认唯一约束建立成功（启动日志无 DDL 错误）。
