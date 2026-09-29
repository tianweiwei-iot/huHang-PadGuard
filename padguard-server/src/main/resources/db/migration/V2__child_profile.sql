-- PadGuard 服务端子账号（孩子）个人资料扩展 (V2)
-- 孩子端可自定义 姓名 / 昵称 / 头像，实时同步至服务端与家长端。

ALTER TABLE devices ADD COLUMN child_name VARCHAR(64);
ALTER TABLE devices ADD COLUMN child_nickname VARCHAR(64);
ALTER TABLE devices ADD COLUMN child_avatar VARCHAR(512);
