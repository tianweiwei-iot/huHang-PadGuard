-- 超时提示方案（家长端「时间管控」页）：DeviceSetting 扩展字段
-- home/local profile（H2）由 ddl-auto=update 自动加列，此迁移用于 docker/postgres 环境
--
-- 语义：孩子端上报 TIME_LIMIT_EXCEEDED（当日额度耗尽）时，服务端按此值执行动作：
--   NONE        不动（沿用既有"仅落告警"行为）
--   NOTIFY_ONLY 仅推送告警给家长端（现状）
--   LOCK        额外下发 LOCK_SCREEN 指令，平板立即锁屏
--   MESSAGE     额外调用信息发布（MessageService.publish），向平板推送霸屏提示
ALTER TABLE device_settings ADD COLUMN time_up_action VARCHAR(20) NOT NULL DEFAULT 'NOTIFY_ONLY';
