package com.padguard.core.engine.enforcer

/**
 * 「单次连续使用」会话的持久化快照。
 *
 * ## 为什么必须落盘
 * 单应用限额里的 `singleMinutes`（"每次最多玩 30 分钟"）此前只靠
 * [AppLimitEnforcer] 的内存字段计时。进程被系统回收、设备重启、
 * 甚至在设置里"强行停止"一次，计时起点就归零 —— 孩子不需要任何工具，
 * 重启一次就能拿到一份全新的 30 分钟。这与 [com.padguard.core.engine.lock.LockController]
 * 对「临时解锁」做的 anti-reboot 处理是同一类问题，这里是它的补齐。
 *
 * ## 字段语义
 * - [accumulatedMs]：本次会话**已累计**的使用时长（单调钟差累加，改系统时间无效）。
 * - [touchWallMs] / [touchMonoMs]：最后一次"仍在本次会话内"的时刻，同一瞬间取样。
 *   恢复时用它算"离开了多久"，决定是**续算**还是**重新起表**：
 *   - 同一次开机（单调钟未归零）：用单调差，改系统时间无法缩短"已离开时长"；
 *   - 设备重启过：单调读数不可比，退回受信墙钟差；
 *   - 两者都算不出来（负数/脏值）：按"没有离开过"处理，即**续算**（宁可少放行，不可多放行）。
 *
 * 刻意不存"是否仍在前台"这类布尔量：恢复时统一用 `now - touch` 判离开时长，
 * 若进程是刚被杀的，`touch` 是十几秒前的值，自然判成"没离开过"→ 续算；
 * 若是隔夜重启，`touch` 是十几小时前的值，自然判成"休息过了"→ 重新起表。
 * 一个量同时表达两种情形，少一个字段就少一处状态不一致。
 */
data class SingleSession(
    val packageName: String,
    val dayKey: String,
    val accumulatedMs: Long,
    val touchWallMs: Long,
    val touchMonoMs: Long
)

/**
 * 单次会话快照的存储抽象。
 *
 * 定义在 engine 模块（纯接口），实现放在 app 模块（[com.padguard.child.time.PrefsSingleSessionStore]）：
 * engine 是 Android library 但没有任何 `Context` 注入，把 SharedPreferences 直接写在这里
 * 会让整个限额模块的 JVM 单测必须跑 Robolectric。
 *
 * ## 实现约定
 * - [loadAll] 必须**永不抛异常**，读失败一律返回空列表（等价于"没有历史会话"）；
 *   抛出去会让 `AppLimitEnforcer.track` 整轮采样失败，而 track 在 runSafely 内，
 *   表现为"管控静默不判定"——比丢一份会话快照严重得多。
 * - [saveAll] 允许失败（磁盘满等），失败只是退化成内存计时，不影响本次运行。
 * - 坏记录必须**单条丢弃**而非整份作废：一条解析失败不该让其余三款的单次限额一起失效。
 */
interface SingleSessionStore {

    /** 读取上次落盘的全部会话；无记录或读失败返回空列表（**不得返回 null**）。 */
    fun loadAll(): List<SingleSession>

    /** 覆盖写入当前会话表。空列表等价于清空。 */
    fun saveAll(sessions: List<SingleSession>)
}
