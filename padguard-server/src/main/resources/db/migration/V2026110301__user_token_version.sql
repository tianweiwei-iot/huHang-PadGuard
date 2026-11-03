-- 家长令牌版本号：支撑「退出登录即吊销」与「后台重置密码后踢掉全部会话」。
--
-- 改造前 /v1/auth/logout 是空实现（直接返回 ok），客户端清掉本地令牌就算登出，
-- 服务端手里的 access token 仍可继续用满 120 分钟，refresh token 更是能一直续期 30 天 ——
-- 令牌一旦泄漏（截图、日志、二手设备），登出这个动作对攻击者毫无影响。
--
-- 登录/续期时把当前值写进 JWT 的 ver 声明，每次请求与库内值比对，落后即视为已吊销。
-- 历史令牌没有 ver 声明，按 0 处理，与本列默认值一致，上线不会把已登录家长集体踢下线。
ALTER TABLE users ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;
