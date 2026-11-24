-- 补齐 usage_sessions 的护眼休息列（新增缺陷 L-01）。
--
-- V25 建表时只有 8 列，后来 UsageSession 实体增加了 rest_accumulated_ms / rest_ids，
-- 并由 UsageSessionService.reportRest / usedMinutesIfActive / toDto 实际读写。
-- 本地 local profile 靠 ddl-auto=update 自动补列，所以开发环境一直发现不了；
-- 但生产（Flyway + ddl-auto=none）用 V25 建出 8 列表后 Hibernate 不会补列，
-- 任何针对该实体的 SELECT/INSERT/UPDATE 都会因缺列报 500，时间管控核心链路整体失效。
-- 全仓所有迁移中此前只有 V25 提到 usage_sessions，没有任何 ALTER 补这两列。
--
-- IF NOT EXISTS 保证：已由 ddl-auto 补过列的存量库重复执行不会报错。
ALTER TABLE usage_sessions
    ADD COLUMN IF NOT EXISTS rest_accumulated_ms BIGINT NOT NULL DEFAULT 0;

ALTER TABLE usage_sessions
    ADD COLUMN IF NOT EXISTS rest_ids VARCHAR(1024) NOT NULL DEFAULT '';
