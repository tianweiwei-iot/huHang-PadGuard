-- 文件归属：给 uploaded_files 补上传者 / 设备。
--
-- 背景：/v1/files/** 为让孩子端能取到家长发布的素材而整体放开了鉴权，
-- 于是这段路径上的儿童截图、录屏、家长导出的报表都变成"拿到 id 即可下载"。
-- 这条路不能简单改回强制 JWT（孩子端加载图片时不带自定义头），
-- 因此改为记录归属 + 凭证存在时精确校验：历史数据（归属为空）仍可用，新数据受保护。
--
-- 兼容 H2(MODE=PostgreSQL) 与 PostgreSQL；语句幂等。
ALTER TABLE uploaded_files ADD COLUMN IF NOT EXISTS owner_user_id VARCHAR(64);
ALTER TABLE uploaded_files ADD COLUMN IF NOT EXISTS device_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_uploaded_files_owner ON uploaded_files(owner_user_id);
CREATE INDEX IF NOT EXISTS idx_uploaded_files_device ON uploaded_files(device_id);
