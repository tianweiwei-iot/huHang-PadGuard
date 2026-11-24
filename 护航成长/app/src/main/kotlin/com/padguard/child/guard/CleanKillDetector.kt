package com.padguard.child.guard

import android.content.Context
import android.os.SystemClock
import com.padguard.core.common.Logger
import com.padguard.core.data.model.RiskLevel
import com.padguard.core.data.model.RiskType
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.data.repository.LogRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 守护进程「被清理 / 异常中断后拉回」的行为检测（2026-11-17 EMUI 一键清理事故闭环）。
 *
 * ## 要解决的问题
 * ROM 的清理白名单（华为：启动管理 + 多任务卡片加锁）是**无查询接口、无系统页面**的
 * 私有设置，装机向导只能文字指引、无法校验家长是否真做完。此前唯一能发现
 * "白名单没配好"的方式是等下一次一键清理把无障碍也带走（那时管控已经裸奔过一轮）。
 *
 * 本检测把「白名单是否生效」变成**行为可观测**：主进程每 15s 向 [GuardHeartbeat]
 * 的 `guard_alive` 文件原子写一条 elapsedRealtime 心跳；若服务启动时发现
 * 「上次心跳就在几分钟内、而本进程是全新创建的」——说明上一次进程是**活着被系统杀掉**
 * （一键清理 / 内存回收 / 滑除卡片 / 崩溃），随即被看门狗/STICKY 拉回。
 * 每次发生都如实上报家长端：白名单配好时本告警永不出现，反复出现 = 防清理设置没生效。
 *
 * ## 判定依据（为什么不会误报）
 * - **设备重启**：elapsedRealtime 归零，`nowMono < prevMono`，不计数（重启是正常事件）；
 * - **全新安装 / 清数据**：心跳文件不存在，不计数；
 * - **用户强停**：强停后的 stopped 态会屏蔽 STICKY/看门狗/闹钟的全部自启路径，
 *   服务根本不会被拉回 —— 能"自己回来"的杀死，只可能是系统清理类动作或崩溃，
 *   正是要观测的对象；
 * - **同进程内服务重建**（onDestroy→onCreate 而进程未死）：用进程级静态闸
 *   [judgedInThisProcess] 保证每个进程只判定一次，重建不重复计数。
 *
 * ## 上报策略
 * 每次命中都值得家长知道（它是防清理设置失效的唯一证据），但给 10 分钟抑制窗
 * 兜住「崩溃循环 + 看门狗竞争拉起」造成的刷屏；按 dayKey 计数，当日第几次写进
 * detail，家长看到"一天第 3 次"比孤立一条更能判断是配置缺失还是偶发内存回收。
 */
@Singleton
class CleanKillDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logRepository: LogRepository,
    private val authRepository: AuthRepository,
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 进程启动时判定一次。必须在 GuardService.onCreate 里、
     * `GuardHeartbeat.writeGuardAlive()` **之前**调用——那次写入会覆盖上次心跳，
     * 之后再读就永远只能看到"刚刚"，判定恒否。
     *
     * 判定与计数同步完成（读一个 KV 文件，微秒级，不阻塞启动路径）；
     * 上行走协程（[GuardService].scope），失败不阻塞服务启动。
     */
    fun judgeOnProcessStart(scope: CoroutineScope) {
        // 进程级闸：同进程内服务重建（进程没死）不是"被清理"，绝不重复判定。
        if (judgedInThisProcess) return
        judgedInThisProcess = true

        val prevMono = GuardHeartbeatSnapshot.readLastGuardAliveMono(context)
        val nowMono = SystemClock.elapsedRealtime()
        if (!killedWhileAlive(prevMono, nowMono, DETECT_WINDOW_MS)) return

        val gapSec = (nowMono - prevMono!!) / 1000
        val dayCount = rollAndBumpCount()
        Logger.w(TAG) {
            "guard process was killed while alive (gap=${gapSec}s, dayCount=$dayCount) " +
                "-> likely memory-clean / LMK / crash; auto-recovered"
        }

        scope.launch {
            report(dayCount, gapSec)
        }
    }

    // ==================== 上报 ====================

    private suspend fun report(dayCount: Int, gapSec: Long) {
        val nowMono = SystemClock.elapsedRealtime()
        // 抑制窗：只在上报成功后才起算（与 raiseDroppedAlert 的去抖纪律一致——
        // 先写时间戳会在 raise 失败时把这次真实事件永久吞掉）。
        if (nowMono - prefs.getLong(KEY_LAST_REPORT_MONO, 0L) < REPORT_SUPPRESS_MS &&
            prefs.getLong(KEY_LAST_REPORT_MONO, 0L) > 0L
        ) {
            return
        }
        runCatching {
            logRepository.raise(
                type = RiskType.GUARD_PROCESS_RECYCLED,
                level = RiskLevel.NORMAL,
                detail = mapOf(
                    "dayCount" to dayCount.toString(),
                    "gapSec" to gapSec.toString(),
                    "note" to NOTE
                ),
                deviceId = authRepository.getDeviceId()
            )
        }.onSuccess {
            prefs.edit().putLong(KEY_LAST_REPORT_MONO, SystemClock.elapsedRealtime()).apply()
        }.onFailure {
            if (it is CancellationException) throw it
            Logger.w(TAG, it) { "failed to raise GUARD_PROCESS_RECYCLED" }
        }
    }

    /**
     * 当日计数（日切滚动）：用受信墙钟不够必要——这里只做展示口径，
     * 用本地日期即可：跨日清零最坏让"今天第几次"重数一次，无安全影响；
     * 不可用 elapsedRealtime 推算日期（没有跨重启锚点）。
     */
    private fun rollAndBumpCount(): Int {
        val today = LocalDate.now().toString()
        if (prefs.getString(KEY_DAY, "") != today) {
            prefs.edit().putString(KEY_DAY, today).putInt(KEY_COUNT, 0).apply()
        }
        val next = prefs.getInt(KEY_COUNT, 0) + 1
        prefs.edit().putInt(KEY_COUNT, next).apply()
        return next
    }

    companion object {
        private const val TAG = "CleanKillDetector"
        private const val PREFS_NAME = "padguard_clean_kill"
        private const val KEY_DAY = "dayKey"
        private const val KEY_COUNT = "dayCount"
        private const val KEY_LAST_REPORT_MONO = "lastReportMono"

        /**
         * 判定窗：上次心跳后 10 分钟内进程重新创建。
         * 主循环 15s 一跳，10 分钟足够覆盖"被杀后看门狗延迟拉回"的全部路径，
         * 又不会把"关机一下午再开机"（早超窗）误算进来。
         */
        internal const val DETECT_WINDOW_MS = 10 * 60_000L

        /** 上报抑制窗：崩溃循环 + 看门狗竞争拉起时防刷屏 */
        internal const val REPORT_SUPPRESS_MS = 10 * 60_000L

        /** 进程级单次判定闸（放 companion：Service 实例会被系统反复重建，进程只有一个） */
        @Volatile
        private var judgedInThisProcess = false

        /**
         * 判定纯函数（JVM 单测锁定全矩阵）。
         *
         * @param prevMono 上次心跳的 elapsedRealtime；null = 从未写过（新装/清数据）
         * @param nowMono 本次进程启动的 elapsedRealtime
         */
        internal fun killedWhileAlive(prevMono: Long?, nowMono: Long, windowMs: Long): Boolean {
            if (prevMono == null || prevMono <= 0L) return false
            // nowMono < prevMono：设备重启后 elapsedRealtime 归零 → 正常重启，不是被杀。
            if (nowMono < prevMono) return false
            return nowMono - prevMono <= windowMs
        }

        /** 家长端提示文案与服务端 AlertService.EVENT_TITLES 同口径（正文以服务端为准）。 */
        internal const val NOTE =
            "守护进程曾被系统中断（一键清理/清理加速、内存回收或异常退出）并已自动恢复。" +
                "若反复出现，说明防清理白名单未生效：请在平板「启动管理」允许本应用自启与后台运行，" +
                "并在最近任务中对本应用卡片下拉加锁。"
    }
}

/**
 * `guard_alive` 心跳的只读快照出口。
 *
 * [com.padguard.child.service.GuardHeartbeat] 在 service 包且部分读者依赖其包内细节，
 * 这里只暴露本检测需要的"上次心跳的 elapsedRealtime 值"一个读数，避免扩大耦合面。
 */
internal object GuardHeartbeatSnapshot {
    fun readLastGuardAliveMono(context: Context): Long? = runCatching {
        java.io.File(context.filesDir, "guard_alive").readText().trim().toLongOrNull()
    }.getOrNull()
}
