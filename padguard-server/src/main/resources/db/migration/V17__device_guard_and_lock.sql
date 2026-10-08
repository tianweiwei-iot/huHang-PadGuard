-- PadGuard 服务端：设备操作密码与远程锁屏列 (V17)
-- 补齐实体 Device 已定义、但历史迁移遗漏的两列：
--   guard_password_hash：设备操作密码哈希（SHA-256，由服务端算好下发，明文永不出网）
--   remote_locked：     家长端远程锁屏状态
-- 走 Flyway（docker / default profile）部署时，Device.toDto() 查询这两列会因列不存在而报错；
-- local / home profile 靠 ddl-auto=update 自动加列，长期掩盖了该缺口，切到生产 Flyway 路径即暴露。
-- 使用 IF NOT EXISTS 保证与既有 ddl-auto 自动加列的库幂等共存。

ALTER TABLE devices ADD COLUMN IF NOT EXISTS guard_password_hash VARCHAR(512);
ALTER TABLE devices ADD COLUMN IF NOT EXISTS remote_locked BOOLEAN NOT NULL DEFAULT FALSE;
