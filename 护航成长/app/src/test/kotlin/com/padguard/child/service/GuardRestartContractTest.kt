package com.padguard.child.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「离线 + 锁屏卡死」故障的契约回归（纯 JVM 源码扫描，不触碰 Android 运行时）。
 *
 * 故障链（2026-11-10 真机现象）：孩子端隐藏形态下 MainActivity 会自行 finish，
 * 系统杀进程后重建的是任务栈顶的 LockScreenActivity；它和 Application.onCreate
 * 都不启动守护，剩余兜底全是后台 Receiver / Job / Alarm（国产 ROM 清通道或后台
 * 启动 FGS 受限时会集体静默失败）→ 整夜 0 心跳、服务端判 OFFLINE、跨日重置也不跑。
 * 即便守护恢复，锁屏页的 isAwaitingStartState 只在 onCreate 取一次快照，
 * DAILY_LIMIT → AWAITING_START 切换后不出现「开始计时」按钮，按 A13 会永久卡死。
 *
 * 本类锁死这两条修复及其边界，防止后续重构把自愈入口又收回到 MainActivity。
 */
class GuardRestartContractTest {

    // ==================== ① 进程级守护自愈 ====================

    @Test
    fun `Application onCreate 必须补拉守护`() {
        val app = application()
        assertTrue(
            "Application.onCreate 没接守护自愈：进程重建后只有 MainActivity 会启动守护，" +
                "而隐藏形态下 MainActivity 已 finish，系统恢复的是锁屏页",
            app.contains("ensureGuardAfterProcessRestart()")
        )
        assertTrue(
            "自愈入口没调用 GuardService.start",
            app.substringAfter("private fun ensureGuardAfterProcessRestart()")
                .contains("GuardService.start(this, GuardService.REASON_PROCESS_RESTART)")
        )
    }

    @Test
    fun `补拉守护必须同时守住已绑定与主进程两道闸`() {
        val app = application()
        val body = app.substringAfter("private fun ensureGuardAfterProcessRestart()")
        assertTrue(
            "未绑定时不能拉起守护：本地陈旧缓存策略会把无归属设备锁死",
            body.contains("if (!GuardHeartbeat.readGuardState(this)) return")
        )
        assertTrue(
            "绑定态必须读文件心跳（跨进程、无需等 DataStore 加载），不能换成 DataStore 读",
            body.contains("GuardHeartbeat.readGuardState")
        )
        assertTrue(
            ":daemon 进程同样执行 Application.onCreate，缺主进程守卫会抢跑拉起主进程",
            body.contains("if (currentProcess != mainProcess) return")
        )
        assertTrue(
            "主进程判断必须基于进程名而非写死包名（debug 包名带 .debug）",
            body.contains("Application.getProcessName()") && body.contains("val mainProcess = packageName")
        )
        assertTrue("API 28 以下需要有版本守卫", body.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.P"))
    }

    @Test
    fun `锁屏页必须在前台上下文补拉守护`() {
        val lock = lockScreen()
        assertTrue("锁屏页没有引入 GuardHeartbeat", lock.contains("import com.padguard.child.service.GuardHeartbeat"))
        val onCreate = lock.substringAfter("override fun onCreate(savedInstanceState: Bundle?)")
            .substringBefore("mode = intent.getIntExtra")
        assertTrue(
            "锁屏页 onCreate 未补拉守护：这是系统重建后唯一有前台 Activity 上下文的入口，" +
                "不受后台启动前台服务限制、也不依赖 Job/Alarm 是否登记",
            onCreate.contains("GuardService.start(this, GuardService.REASON_PROCESS_RESTART)")
        )
        assertTrue(
            "锁屏页补拉守护必须先判绑定态",
            onCreate.contains("GuardHeartbeat.readGuardState(this)")
        )
    }

    @Test
    fun `REASON_PROCESS_RESTART 存在且不做策略重压`() {
        val service = guardService()
        assertTrue(
            "缺少 REASON_PROCESS_RESTART 常量",
            service.contains("const val REASON_PROCESS_RESTART = \"processRestart\"")
        )
        val onStart = service.substringAfter("override fun onStartCommand(")
            .substringBefore("return START_STICKY")
        assertTrue(
            "onStartCommand 未处理 REASON_PROCESS_RESTART：会落到默认分支重复 applyCurrent(force=true)",
            onStart.contains("reason == REASON_PROCESS_RESTART")
        )
        assertTrue(
            "补拉分支应为空操作（bootstrap 已完成初始化），不能触发额外管控动作",
            onStart.substringAfter("reason == REASON_PROCESS_RESTART)")
                .substringBefore("} else if (reason == REASON_FLUSH)")
                .trim().endsWith("Unit")
        )
    }

    @Test
    fun `GuardService start 保持幂等且吞掉后台启动异常`() {
        val service = guardService()
        val start = service.substringAfter("fun start(context: Context, reason: String)")
            .substringBefore("fun refreshPermissionNotice")
        assertTrue("start 必须自带 runCatching（后台 FGS 被拒不能崩进程）", start.contains("runCatching"))
        assertTrue("API 26+ 必须走 startForegroundService", start.contains("startForegroundService(intent)"))
    }

    // ==================== ② 跨日后锁屏页状态刷新 ====================

    @Test
    fun `锁屏页主锁定原因必须在 state collect 里实时跟随`() {
        val lock = lockScreen()
        val collector = lock.substringAfter("lockController.state.collect { state ->")
            .substringBefore("if (!state.locked)")
        assertTrue(
            "跨日重置 DAILY_LIMIT → AWAITING_START 后，只读一次快照会让页面永远停在" +
                "「今日额度已用完」且不显示开始计时按钮（A13 未确认永不开始 → 平板卡死）",
            collector.contains("isAwaitingStartState = state.primaryReason == LockReason.AWAITING_START")
        )
        assertTrue(
            "护眼休息进出同样需要实时切换主题与倒计时",
            collector.contains("isEyeCareState = state.primaryReason == LockReason.EYE_CARE")
        )
        assertTrue(
            "暂停计时与等待页/护眼同型：主原因变化必须实时切换专属主题",
            collector.contains("isTimerPausedState = state.primaryReason == LockReason.TIMER_PAUSED")
        )
    }

    @Test
    fun `等待开始计时标记不能只在 onCreate 赋值一次`() {
        val lock = lockScreen()
        val assignments = Regex("isAwaitingStartState\\s*=\\s*").findAll(lock).count()
        assertTrue(
            "isAwaitingStartState 只有 $assignments 处赋值，说明又退回 onCreate 一次性快照",
            assignments >= 2
        )
        val eyeAssignments = Regex("isEyeCareState\\s*=\\s*").findAll(lock).count()
        assertTrue("isEyeCareState 只有 $eyeAssignments 处赋值", eyeAssignments >= 2)
    }

    @Test
    fun `跨日锁屏专属主题下必须清掉过期的通用副文案`() {
        val lock = lockScreen()
        val collector = lock.substringAfter("lockController.state.collect { state ->")
            .substringBefore("if (!state.locked)")
        assertTrue(
            "AWAITING_START / EYE_CARE / TIMER_PAUSED 下未清 detailState：会出现「今日使用计时尚未开始」" +
                "标题下挂着过期的「使用时间已用完」副文案，造成困惑",
            collector.contains(
                "if (isAwaitingStartState || isEyeCareState || isTimerPausedState) detailState = \"\""
            )
        )
    }

    // ==================== ③ 联网恢复主动回拉（离线脱困最后一公里） ====================

    @Test
    fun `联网恢复必须主动回拉策略与会话`() {
        val svc = guardService()
        val branch = svc.substringAfter("is Downlink.ConnectivityChanged ->")
        assertTrue("ConnectivityChanged 分支缺少联网判定", branch.contains("if (event.online)"))
        val onlineBlock = branch.substringAfter("if (event.online)")
        assertTrue(
            "联网恢复后未回拉策略：服务端解锁 / 改策略 / 跨日已被家长起表会滞后到下一轮 tick（最长 15s），" +
                "表现为「联网了却还卡在旧锁屏」",
            onlineBlock.contains("refreshPolicy()")
        )
        assertTrue(
            "联网恢复后未回拉会话：离线本地起表（pendingStartSync）不会即时补发给服务端，" +
                "家长端看不到今日计时起点、对账缺口",
            onlineBlock.contains("usageSessionManager.refresh()")
        )
        assertTrue(
            "回拉不应阻塞下行采集协程：必须用 scope.launch 异步执行",
            onlineBlock.contains("scope.launch")
        )
        assertTrue(
            "联网恢复后未主动兜底拉取离线指令：家长在掉线期间下发的 UNLOCK / START_TIMER_CONFIRM " +
                "只会等 startCommandLoop 下一跳（≤3s）或心跳提示（≤30s），表现为「点了没反应」",
            onlineBlock.contains("drainPendingCommands()")
        )
    }

    // ==================== ⑥ 锁屏页亮屏即本地日切（跨日不再卡死） ====================

    @Test
    fun `锁屏页 onResume 必须主动评估日切`() {
        val lock = lockScreen()
        val onResume = lock.substringAfter("override fun onResume()")
        val body = onResume.substringBefore("override fun on")
        assertTrue(
            "锁屏页 onResume 没调用 checkDayRollover：孩子次日亮屏仍要等下一轮 Guard tick（≤15s）" +
                "才把昨天的 DAILY_LIMIT 重置为今日待确认，期间锁屏页停在旧文案且不给「开始计时」",
            body.contains("checkDayRollover()")
        )
        assertTrue(
            "日切评估必须容错：onResume 在主线程，checkDayRollover 含 prefs 读写，需 runCatching 兜底",
            body.contains("runCatching")
        )
    }

    // ==================== ④ 回归：旧兜底通道不许被删 ====================

    @Test
    fun `MainActivity 原有的两个兜底入口仍在`() {
        val main = readText(
            "src/main/kotlin/com/padguard/child/MainActivity.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/MainActivity.kt"
        )
        assertTrue("hiddenEntry 兜底被删", main.contains("GuardService.start(context, \"hiddenEntry\")"))
        assertTrue("mainEntry 兜底被删", main.contains("GuardService.start(context, \"mainEntry\")"))
    }

    @Test
    fun `后台兜底通道仍全部保留 新入口只是补充不是替换`() {
        val channels = listOf(
            "src/main/kotlin/com/padguard/child/receiver/BootReceiver.kt" to "GuardService.start(context, reason = reason)",
            "src/main/kotlin/com/padguard/child/receiver/DayRolloverReceiver.kt" to "GuardService.start(context, GuardService.REASON_DAY_ROLLOVER)",
            "src/main/kotlin/com/padguard/child/receiver/GuardAlarmReceiver.kt" to "GuardService.start(context, GuardService.REASON_ALARM)",
            "src/main/kotlin/com/padguard/child/service/GuardDaemonService.kt" to "GuardService.start(this, GuardService.REASON_DAEMON)",
            "src/main/kotlin/com/padguard/child/service/GuardWatchdogService.kt" to "GuardService.start(this, GuardService.REASON_WATCHDOG)"
        )
        channels.forEach { (path, call) ->
            val source = readText(path, "护航成长/app/$path")
            assertTrue("$path 的守护拉起调用被删：$call", source.contains(call))
        }
    }

    @Test
    fun `时间变化广播必须即时全量重铺管控 挡住同日内改时间跨时段边界`() {
        val gs = guardService()
        val start = gs.indexOf("reason == REASON_DAY_ROLLOVER")
        assertTrue("找不到 REASON_DAY_ROLLOVER 分支", start >= 0)
        // 取分支片段（含注释与代码，约 1600 字符足够覆盖整个分支体）。
        val branch = gs.substring(start, minOf(start + 1600, gs.length))
        assertTrue(
            "时间变化只做了日切对账、没跑 applyCurrent(force=true)：孩子把锁屏时刻往前拨 2 小时" +
                "想逃锁时，要等最长 15s tick 才重算，期间处于解锁空窗",
            branch.contains("applyCurrent(force = true)")
        )
        assertTrue(
            "时间变化未重铺锁屏（reenforce）：改时间想逃锁时锁屏页不会秒级顶回",
            branch.contains("reenforce()")
        )
        assertTrue(
            "时间变化未保留日切对账：跨零点场景依赖 checkDayRollover 立即重置日 key",
            branch.contains("checkDayRollover()")
        )
    }

    @Test
    fun `网络恢复必须即刻自愈 重连并同步策略`() {
        val gs = guardService()
        // 1) CONNECTIVITY_ACTION 必须真正进入自愈分支（此前只注册不处理，恢复后干等心跳）。
        val connIdx = gs.indexOf("ConnectivityManager.CONNECTIVITY_ACTION ->")
        assertTrue("restartReceiver 注册了 CONNECTIVITY_ACTION 却没有处理分支", connIdx >= 0)
        // 取该分支片段（含注释与代码，约 900 字符足够覆盖整个分支体）。
        val connBranch = gs.substring(connIdx, minOf(connIdx + 900, gs.length))
        assertTrue(
            "CONNECTIVITY_ACTION 分支没有调用 onConnectivityRestored：" +
                "孩子开飞行模式/关 Wi-Fi 再恢复时，设备会干等下一轮心跳+Paho 退避而'失联'十几秒",
            connBranch.contains("onConnectivityRestored()")
        )
        // 仅在"网络可用"时自愈：避免断网瞬间也被当成恢复来 churn。
        assertTrue(
            "CONNECTIVITY_ACTION 分支未区分 EXTRA_NO_CONNECTIVITY，断网瞬间也会误触发恢复",
            connBranch.contains("EXTRA_NO_CONNECTIVITY")
        )
        // 2) 自愈方法必须落地三步：标记可达 + 立即排空指令 + 强制重连并同步策略。
        val methodIdx = gs.indexOf("private fun onConnectivityRestored()")
        assertTrue("缺少 onConnectivityRestored 自愈方法", methodIdx >= 0)
        val method = gs.substring(methodIdx, minOf(methodIdx + 1400, gs.length))
        assertTrue(
            "未标记 httpReachable=true：指令拉取循环不会切回快速路径、实时指令仍要等 idle 跳",
            method.contains("httpReachable = true")
        )
        assertTrue(
            "未立即重启指令拉取循环（startCommandLoop）：实时指令不在同一拍排空",
            method.contains("startCommandLoop()")
        )
        assertTrue(
            "未强制重连传输（remote.start）：Paho 重连退避期间 MQTT 仍收不到推送",
            method.contains("remote.start()")
        )
        assertTrue(
            "未立即同步策略（refreshPolicy）：家长刚改的管控要等下一轮 fetchPolicy 才生效",
            method.contains("refreshPolicy()")
        )
        assertTrue(
            "未绑定或未同意协议的设备都不应自愈（无管控依据），必须过 boundNow 执行面闸门",
            method.contains("boundNow.get()")
        )
    }

    @Test
    fun `锁屏页不得因自愈改动放开返回键或状态栏封锁`() {
        val lock = lockScreen()
        assertTrue("返回键拦截被删（孩子可逃逸锁屏）", lock.contains("OnBackPressedCallback(true)"))
        assertTrue("状态栏封锁被删（可从通知栏进设置卸载）", lock.contains("applyStatusBarDisabled(true)"))
        assertFalse(
            "锁屏页不该自己直接 stop GuardService（解锁由 LockController 状态驱动）",
            lock.contains("GuardService.stop(")
        )
    }

    // ==================== 工具 ====================

    private fun application() = readText(
        "src/main/kotlin/com/padguard/child/PadGuardApplication.kt",
        "护航成长/app/src/main/kotlin/com/padguard/child/PadGuardApplication.kt"
    )

    private fun lockScreen() = readText(
        "src/main/kotlin/com/padguard/child/ui/lock/LockScreenActivity.kt",
        "护航成长/app/src/main/kotlin/com/padguard/child/ui/lock/LockScreenActivity.kt"
    )

    @Test
    fun `前台服务启动被拒必须上报告警 不得静默降级为普通后台服务`() {
        val gs = guardService()
        assertTrue(
            "必须识别 Android 12+ 的 FGS 后台启动拒绝异常",
            gs.contains("ForegroundServiceStartNotAllowedException")
        )
        assertTrue(
            "FGS 被拒后必须上报告警：旧实现只打日志、心跳照写，" +
                "家长端看到设备在线却随时会被回收（静默失效）",
            gs.contains("RiskType.GUARD_FOREGROUND_DOWN") && gs.contains("logRepository.raise")
        )

        val events = readText(
            "src/main/kotlin/com/padguard/core/data/model/Event.kt",
            "护航成长/core/data/src/main/kotlin/com/padguard/core/data/model/Event.kt"
        )
        assertTrue("RiskType 枚举缺少 GUARD_FOREGROUND_DOWN", events.contains("GUARD_FOREGROUND_DOWN"))
        val titles = readText(
            "src/main/kotlin/com/padguard/core/data/repository/LogRepository.kt",
            "护航成长/core/data/src/main/kotlin/com/padguard/core/data/repository/LogRepository.kt"
        )
        assertTrue("LogRepository.alertTitle 缺少降级告警中文标题", titles.contains("RiskType.GUARD_FOREGROUND_DOWN ->"))
    }

    private fun guardService() = readText(
        "src/main/kotlin/com/padguard/child/service/GuardService.kt",
        "护航成长/app/src/main/kotlin/com/padguard/child/service/GuardService.kt"
    )

    /** Gradle 单测 cwd 可能是模块目录或仓库根，逐级向上找，避免 IDE / 命令行差异造成假红。 */
    private fun readText(vararg candidates: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        for (relative in candidates) {
            val direct = File(relative)
            if (direct.isFile) return direct.readText()
            var dir: File? = cwd
            repeat(6) {
                if (dir == null) return@repeat
                val candidate = File(dir, relative)
                if (candidate.isFile) return candidate.readText()
                dir = dir!!.parentFile
            }
        }
        throw AssertionError("找不到源码文件：${candidates.joinToString()}；cwd=$cwd")
    }
}
