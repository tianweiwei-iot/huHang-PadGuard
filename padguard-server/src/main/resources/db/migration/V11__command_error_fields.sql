-- P0-5 可观测性：指令回执的错误码 / 错误描述落库
-- 用途：家长端轮询 getCommand 时能看到"指令为什么没生效"（REJECTED / UNSUPPORTED / FAILED 的具体原因）。
-- 说明：H2（local/home profile）由 ddl-auto=update 自动加列；此处仅覆盖 PostgreSQL（docker/default profile，Flyway 启用）。
-- 字段在原 Command 实体上新增，均为可空，对历史指令行无影响。

ALTER TABLE commands ADD COLUMN IF NOT EXISTS error_code VARCHAR(64);
ALTER TABLE commands ADD COLUMN IF NOT EXISTS error_message TEXT;
