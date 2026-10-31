-- 第八轮评估 P1-B：订阅表 user_id 唯一约束
--
-- 此前 subscriptions.user_id 只有普通索引（见 V2026101101__billing_tables.sql），
-- statusOf() 未命中时 createTrial() 与并发请求竞态可为同一家长插入两条订阅，
-- 而所有读取路径都用 findByUserId（单条语义），第二条订阅会成为永远读不到的孤儿，
-- 且 extend 只续到其中一条上——另一条的到期时间停在旧值，家长视角"付费没生效"。
--
-- 加唯一约束后，并发 createTrial 只有一个事务能插入成功，另一个走回退路径。
-- 生产上线前若已存在重复数据，须先人工去重（保留 expired_at 最大的那条）再执行迁移；
-- 迁移脚本对已有重复数据会直接失败——这是有意的失败（fail-fast），提示先去重。

CREATE UNIQUE INDEX IF NOT EXISTS ux_subscriptions_user_id ON subscriptions (user_id);
