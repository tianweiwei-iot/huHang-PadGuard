-- 订阅权益闸门记账位（Device.entitlementState）。
--
-- 为什么必须补这条迁移：生产 Postgres 跑 `ddl-auto=none`，列只由 Flyway 创建；
-- 本地 H2（local/home 档位）才是 Hibernate 自动建列。实体加了 entitlement_state 却没配套
-- 迁移，生产库 devices 表就没有这一列 —— 而 Device 是**每一个**孩子端接口都要加载的实体，
-- SELECT/UPDATE devices 会直接抛 "column does not exist"，家长端设备列表、心跳、策略下发、
-- 绑定/解绑全线 500。与 V2026110702（usage_session 的 rest 列）是同一类坑，勿再漏。
--
-- 默认值必须留在 DDL 里：ADD COLUMN 时存量行取 DEFAULT，但若列已存在（例如有人手工加过）
-- 则可能全是 NULL，而 Kotlin 侧 entitlementState 是非空 String，读出 NULL 会抛异常，
-- 所以下面再补一条 UPDATE 兜底。业务口径上"从未翻转过的设备"就是 ACTIVE。
ALTER TABLE devices ADD COLUMN IF NOT EXISTS entitlement_state VARCHAR(20) DEFAULT 'ACTIVE';

UPDATE devices SET entitlement_state = 'ACTIVE' WHERE entitlement_state IS NULL;
