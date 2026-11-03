-- 后台聚合查询的补索引
--
-- 背景：仪表盘 / 订阅列表 / 订单列表 / 审计列表原本都是「全表读进内存再过滤」，
-- 所以从不需要这些索引 —— 反正整表都要读。本轮把这些查询改成 count / group by /
-- 分页下推之后，索引才会真正被用上：没有索引的下推只是把「读全表到 JVM」换成
-- 「读全表到数据库」，慢的地方换了个位置，运营侧体感不会变好。
--
-- 同理，V12 那份迁移的注释仍然成立：实体上的 @Index 只对 ddl-auto 建表生效，
-- 生产是 ddl-auto=none + Flyway，索引必须由迁移创建。
--
-- ⚠️ 上线注意：PostgreSQL 的 CREATE INDEX 会持有 ShareLock，建索引期间该表写入阻塞。
--    本项目的 users / devices / subscriptions / payment_orders 都是小表（万级以内）时无感；
--    如果线上某张表已到百万级，请先手工执行 CREATE INDEX CONCURRENTLY（不能放在事务里，
--    所以无法写进 Flyway 迁移），再用 flyway repair 对齐版本号。判断依据见报告。

-- 趋势分桶：`where created_at >= :since` 只取一列，走索引即可完成范围扫描
CREATE INDEX IF NOT EXISTS idx_users_created_at ON users (created_at);

-- 账号状态计数（含 coalesce(status,'active') 的两种取值）
CREATE INDEX IF NOT EXISTS idx_users_status ON users (status);

-- 设备趋势 + 在线数（在线数查询同时用到 online_status 与 last_online_at 两个条件）
CREATE INDEX IF NOT EXISTS idx_devices_created_at ON devices (created_at);
CREATE INDEX IF NOT EXISTS idx_devices_online_heartbeat ON devices (online_status, last_online_at);

-- 订阅：状态过滤（列表 + 看板计数）与创建时间（趋势分桶）
CREATE INDEX IF NOT EXISTS idx_subscriptions_status ON subscriptions (status);
CREATE INDEX IF NOT EXISTS idx_subscriptions_created_at ON subscriptions (created_at);

-- 订单：营收窗口查询是 `status = 'PAID' and created_at >= :since`，
-- 后台订单列表是 `status = ? order by created_at desc` —— 同一条复合索引两处都能用。
-- （V2026101101 建的是 (user_id, created_at)，前缀是 user_id，对上面两种查询用不上。）
CREATE INDEX IF NOT EXISTS idx_orders_status_created ON payment_orders (status, created_at);

-- 审计日志：后台列表是 `action in (...) order by created_at desc`，
-- 以及不带领域时的 `order by created_at desc`。复合索引让两者都能靠索引顺序直接取前 N 条，
-- 不必排序整表 —— 审计表是全项目增长最快的表之一。
CREATE INDEX IF NOT EXISTS idx_audit_logs_action_created ON audit_logs (action, created_at);
CREATE INDEX IF NOT EXISTS idx_audit_logs_created_at ON audit_logs (created_at);
