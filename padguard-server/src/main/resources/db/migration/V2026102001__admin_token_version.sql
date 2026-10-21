-- 管理员令牌版本号：支撑「退出登录即吊销」与「改密码后踢掉全部会话」。
-- 登录时把当前值写进 JWT 的 ver 声明，请求时与库内值比对，不符即视为已吊销。
-- 历史令牌无 ver 声明，按 0 处理，与本列默认值一致，不会误杀存量会话。
ALTER TABLE admins ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;
