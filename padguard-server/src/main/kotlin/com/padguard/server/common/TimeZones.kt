package com.padguard.server.common

import java.time.ZoneId

/**
 * 服务端统一时区。
 *
 * 所有"按中国时区展示 / 计算"的逻辑统一引用 [SERVER_ZONE_ID] 这一单一事实源，
 * 避免在各处用 `ZoneId.of("Asia/Shanghai")` 魔法字符串重复声明，也避免
 * `ZoneId.systemDefault()` 在宿主机 TZ=UTC / docker 未锁 TZ 时导致日志时间戳偏 8 小时。
 *
 * 注意：下发给被管控端 App 的 `schedule.timezone` 仍是 JSON 字符串契约，保持字面量 "Asia/Shanghai"
 * （那是两端序列化约定，改值会破坏对孩子的策略包），不在此处替换。
 */
/**
 * 时区 ID 字面量，供要求编译期常量的注解属性使用（如
 * `@Scheduled(cron = "...", zone = SERVER_ZONE)`）。
 *
 * 单独存在的原因：注解参数不能传 [SERVER_ZONE_ID] 这种运行期对象，只能传常量字符串；
 * 用同一个字面量初始化 [SERVER_ZONE_ID]，保证"注解口径"与"代码口径"不会各自演化成两个时区。
 */
const val SERVER_ZONE: String = "Asia/Shanghai"

/**
 * 服务端统一时区（北京时间 / UTC+8）。
 *
 * Asia/Shanghai 自 1991 年起不再实行夏令时（`getRules().nextTransition()` 返回 null），
 * 因此全年恒定 +08:00，等价于 UTC+8，不存在季节性偏移。
 */
val SERVER_ZONE_ID: ZoneId = ZoneId.of(SERVER_ZONE)
