-- 分布式定时任务互斥锁（租约制）。
--
-- 背景：全部 @Scheduled 清理任务（RetentionCleanup / UsageLogCleanup 等）原本没有任何
-- 多副本互斥——本地 H2 单副本部署下无感，但 docker/生产档按 PG 多副本部署时，
-- 每个副本都会到点执行同一批清理：并发删除交错可能误删"另一副本正在引用的文件"，
-- 重复扫盘也放大 IO。租约锁保证同一任务同一时刻只有一个副本真正执行。
--
-- 语义：
--   抢锁 = UPDATE 到期租约 或 首次 INSERT；拿不到（他副本持有效租约）即本轮放弃。
--   崩溃自愈 = 租约到期后任何副本都可重新抢占，不需要人工清锁。
--   释放 = 只删自己 holder 的行，绝不碰别人的租约。
-- 全部 DDL 用 IF NOT EXISTS，H2(MODE=PostgreSQL) 与 PG16 双兼容。
-- 不用 pg_advisory_lock：H2 不支持，锁实现必须可移植。

CREATE TABLE IF NOT EXISTS task_locks (
    task_name   VARCHAR(64)  PRIMARY KEY,
    holder      VARCHAR(128) NOT NULL,
    acquired_at BIGINT       NOT NULL,
    lease_until BIGINT       NOT NULL
);
