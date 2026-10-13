package com.padguard.server.common

import java.time.ZoneId

/**
 * 服务端统一时区。
 *
 * W81：此前时区在多处用 `ZoneId.of("Asia/Shanghai")` 魔法字符串重复声明，且历史上出现过
 * `ZoneId.systemDefault()` 在宿主机 TZ=UTC / docker 未锁 TZ 时导致日志时间戳偏 8 小时的问题。
 * 这里收敛为单一事实源：所有"按中国时区展示 / 计算"的逻辑统一引用 [SERVER_ZONE_ID]。
 *
 * 注意：下发给被管控端 App 的 `schedule.timezone` 仍是 JSON 字符串契约，保持字面量 "Asia/Shanghai"
 * （那是两端序列化约定，改值会破坏对孩子的策略包），不在此处替换。
 */
val SERVER_ZONE_ID: ZoneId = ZoneId.of("Asia/Shanghai")
