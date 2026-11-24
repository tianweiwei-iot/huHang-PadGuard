package com.padguard.child.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * AB 锁（双进程互绑）接线契约。
 *
 * ## 背景：用户实测「一键清理后系统再也起不来」
 * 排查发现守护链本身是完整的（daemon 5s 心跳监视、JobScheduler 看门狗 60~90s、
 * AlarmManager 闹钟 60s + 1.2s 即时兜底、开机广播），但**所有自恢复都只走
 * `startForegroundService`**：两个服务的 `onBind` 都返回 `null`，binder 通道完全没接。
 * 那条路有两个硬伤 —— API 31+ 从后台（JobService / 广播回调）启动前台服务会抛
 * `ForegroundServiceStartNotAllowedException`；国产 ROM 还会按「自启」直接拦截且无异常。
 * 于是清理把两个进程一起杀掉之后，三条通道全被系统挡住，表现为"装了管控却永久脱管"。
 *
 * 修法是给两个服务都开口 `onBind`（返回 Messenger），并用 [GuardLink] 双向绑定：
 * `bindService(BIND_AUTO_CREATE)` 会在目标进程不存在时**创建进程**，且不受上述两条限制。
 *
 * 这些接线一旦被人"顺手清理成 return null"，故障是静默的（要等真机一键清理才复现），
 * 所以用源码扫描钉死。
 */
class GuardLinkContractTest {

    @Test
    fun `两个守护服务都必须暴露 binder（onBind 不得返回 null）`() {
        val guard = readText(
            "src/main/kotlin/com/padguard/child/service/GuardService.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/service/GuardService.kt"
        )
        val daemon = readText(
            "src/main/kotlin/com/padguard/child/service/GuardDaemonService.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/service/GuardDaemonService.kt"
        )

        assertFalse(
            "GuardService.onBind 返回 null 会断掉 AB 锁的 A 端点（daemon 无法把它绑回来）",
            guard.contains("override fun onBind(intent: Intent?): IBinder? = null")
        )
        assertTrue("GuardService.onBind 必须返回 binder", guard.contains("return linkMessenger"))
        assertTrue(
            "被 binder 唤起时不会走 onStartCommand，必须自己补 bootstrap",
            guard.contains("private fun wakeFromBinder()")
        )
        assertTrue(
            "wakeFromBinder 必须触发 bootstrap（否则进程在、服务在、主循环不跑）",
            guard.contains("bootstrap(REASON_BIND)")
        )

        assertFalse(
            "GuardDaemonService.onBind 返回 null 会断掉 AB 锁的 B 端点",
            daemon.contains("override fun onBind(intent: Intent?): IBinder? = null")
        )
        assertTrue("GuardDaemonService.onBind 必须返回 binder", daemon.contains("return linkMessenger"))
        assertTrue(
            "被 binder 唤起时也要补齐监视循环",
            daemon.contains("private fun ensureWatching()")
        )
    }

    @Test
    fun `绑定必须带 BIND_AUTO_CREATE 否则不会创建目标进程`() {
        val link = readText(
            "src/main/kotlin/com/padguard/child/service/GuardLink.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/service/GuardLink.kt"
        )
        assertTrue(
            "bindService 缺少 BIND_AUTO_CREATE 时，目标进程不存在就不会被创建 —— AB 锁失效",
            link.contains("Context.BIND_AUTO_CREATE")
        )
        // 双向都要有：只绑一边等于单点
        assertTrue(link.contains("GuardDaemonService::class.java"))
        assertTrue(link.contains("GuardService::class.java"))
        // binder 死亡后必须重绑，否则系统复用死连接、永不恢复
        assertTrue(link.contains("onBindingDied"))
        assertTrue(link.contains("fun releaseAll(context: Context)"))
    }

    @Test
    fun `三条系统自恢复通道必须优先走 bind`() {
        val watchdog = readText(
            "src/main/kotlin/com/padguard/child/service/GuardWatchdogService.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/service/GuardWatchdogService.kt"
        )
        val alarm = readText(
            "src/main/kotlin/com/padguard/child/receiver/GuardAlarmReceiver.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/receiver/GuardAlarmReceiver.kt"
        )
        val guard = readText(
            "src/main/kotlin/com/padguard/child/service/GuardService.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/service/GuardService.kt"
        )

        assertTrue(
            "看门狗必须先 bind 再 start（bind 不受后台启动 FGS 限制）",
            watchdog.contains("GuardLink.wakeGuard(this)")
        )
        assertTrue(
            "看门狗拉 daemon 也要走 bind",
            watchdog.contains("GuardLink.wakeDaemon(this)")
        )
        assertTrue(
            "闹钟通道必须先 bind（清理后最先触发的就是它）",
            alarm.contains("GuardLink.wakeGuard(context)")
        )
        assertTrue(
            "主循环发现 daemon 心跳陈旧时也要 bind 优先",
            guard.contains("GuardLink.ensureDaemonBound(this@GuardService)")
        )
    }

    @Test
    fun `解绑设备必须释放双向绑定 否则服务杀不掉`() {
        val guard = readText(
            "src/main/kotlin/com/padguard/child/service/GuardService.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/service/GuardService.kt"
        )
        val daemon = readText(
            "src/main/kotlin/com/padguard/child/service/GuardDaemonService.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/service/GuardDaemonService.kt"
        )

        assertTrue(
            "解绑后不释放绑定，两个服务会因「仍有绑定」而无法销毁（stopSelf 失效），" +
                "剩下两条常驻通知 —— 这是隐私事故",
            guard.contains("GuardLink.releaseAll(this@GuardService)")
        )
        assertTrue(
            "daemon 判定未绑定、准备自停前必须先解绑主进程",
            daemon.contains("GuardLink.releaseGuard(this)")
        )
        // 家长主动解绑（退出管控）那条路径最后会 stopSelf：有绑定的服务 stopSelf 不生效，
        // 不解绑就会留下"已解绑但仍在跑"的守护服务 + 常驻通知。
        val stopIdx = guard.lastIndexOf("stopSelf()")
        val releaseIdx = guard.lastIndexOf("GuardLink.releaseAll(this)")
        assertTrue(
            "解绑收尾必须在 stopSelf 之前释放 AB 锁（否则 stopSelf 不生效，服务退出不了）",
            stopIdx > 0 && releaseIdx in 0 until stopIdx
        )
    }

    @Test
    fun `daemon 必须真的跑在独立进程里`() {
        val manifest = readText(
            "src/main/AndroidManifest.xml",
            "护航成长/app/src/main/AndroidManifest.xml"
        )
        assertTrue(
            "GuardDaemonService 缺 android:process=\":daemon\" 时会跑在主进程里，" +
                "整体被杀时一并死亡，AB 锁与心跳监视全部失效",
            manifest.contains("android:process=\":daemon\"")
        )
    }

    /**
     * 2026-11-17 加固三项的源码锁（详见 GuardLink 类注释「加固三项」）：
     * 1. onServiceDisconnected 必须与 bindingDied 一样先 unbind 再重绑 ——
     *    BIND_AUTO_CREATE 的系统自动重启会复用同一条连接重连，只重绑不解绑会叠加
     *    bind 计数，releaseAll 解不干净 → 解绑设备后目标服务永远杀不掉（常驻通知泄漏）；
     * 2. 绑定发起必须有超时兜底 —— bindService 返回 true 不代表绑定会建立（目标 onCreate
     *    崩溃 / ROM 静默拦截重启时永远没有回调），binding 挂死即全部兜底通道哑火；
     * 3. release 必须按 bind 计数解干净，只解一层会留下杀不掉的服务。
     */
    @Test
    fun `断连必须先解绑再重绑 禁止残留 needUnbind = false 路径`() {
        val link = readText(
            "src/main/kotlin/com/padguard/child/service/GuardLink.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/service/GuardLink.kt"
        )
        // 两侧 × (disconnected + bindingDied + nullBinding) = 至少 6 处全部解绑
        val unbindPaths = Regex("needUnbind = true").findAll(link).count()
        assertTrue(
            "onServiceDisconnected/onBindingDied/onNullBinding 三路断连（双侧共 6 处）" +
                "必须全部 needUnbind = true，实测 $unbindPaths 处；存在 needUnbind = false 的" +
                "路径即 bind 计数叠加漏洞（解绑设备后服务杀不掉、常驻通知泄漏）",
            unbindPaths >= 6
        )
        assertFalse(
            "不允许存在 needUnbind = false 的断连分支（disconnected 不解绑是旧 bug）",
            link.contains("needUnbind = false")
        )
    }

    @Test
    fun `绑定发起必须有超时兜底 否则 ROM 静默拦截时永久挂死`() {
        val link = readText(
            "src/main/kotlin/com/padguard/child/service/GuardLink.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/service/GuardLink.kt"
        )
        assertTrue(
            "缺 BIND_TIMEOUT_MS 超时兜底：bindService 接受但回调永不到（目标 onCreate 崩溃 /" +
                " ROM 拦截重启）时 binding 标志永久挂死，start 兜底与重绑全部哑火 ——" +
                "外部表现即「清理后永久脱管」",
            link.contains("BIND_TIMEOUT_MS")
        )
        assertTrue(
            "超时兜底必须双侧都有（只保一侧另一侧仍会挂死）",
            link.contains("scheduleDaemonBindTimeout") && link.contains("scheduleGuardBindTimeout")
        )
        assertTrue(
            "超时判定必须同时检查 wanted && binding && !bound（已连上后误触发会拆健康连接）",
            link.contains("daemonWanted && daemonBinding && !daemonBound") &&
                link.contains("guardWanted && guardBinding && !guardBound")
        )
    }

    /**
     * 2026-11-17 二次复查三项（详见当日日志 R52）：
     * 1. daemon 常在「未绑定家长」时创建（GuardService.onCreate 无条件绑 daemon），
     *    onCreate 里 readGuardState=false 会跳过 B→A 绑定；家长绑定后主进程
     *    ensureDaemonBound 命中「已连接」早返回，daemon 的 ensureWatching 必须补建
     *    反向绑定，否则 AB 锁是半锁（主进程被杀没有 binder 即时死亡事件）；
     * 2. watchLoop 的解绑自停必须先于 `if (!stale) continue`，否则设备解绑但主进程
     *    心跳新鲜时 daemon 永不自停（绑定不释放 → 僵尸服务 + 常驻通知）；
     * 3. GuardService.onDestroy 必须兜底 releaseAll（非正常解绑路径的销毁同样要解绑）。
     */
    @Test
    fun `daemon 补建 B→A 绑定 防正常装机路径退化成半锁`() {
        val daemon = readText(
            "src/main/kotlin/com/padguard/child/service/GuardDaemonService.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/service/GuardDaemonService.kt"
        )
        val link = readText(
            "src/main/kotlin/com/padguard/child/service/GuardLink.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/service/GuardLink.kt"
        )
        val watching = daemon.substringAfter("private fun ensureWatching() {")
            .substringBefore("\n    }")
        assertTrue(
            "ensureWatching（onBind / MSG_WAKE / onStartCommand 都会走）必须在已绑定设备上" +
                "补建 B→A 绑定：否则 daemon 在未绑定时创建后永远没有反向 binder，" +
                "AB 锁退化成半锁（主进程被杀只能等 5s 心跳轮询，最坏 ~40s）",
            watching.contains("GuardLink.ensureGuardBound(this)")
        )
        assertTrue(
            "补建 B→A 绑定必须受 guard_state 门控（未绑定设备不拉主进程，无管控依据）",
            watching.contains("GuardHeartbeat.readGuardState(this)")
        )
        // 已连接早返回时主进程必须 ping daemon 触发上述补建
        val ensure = link.substringAfter("fun ensureDaemonBound(context: Context): Boolean = synchronized(stateLock) {")
            .substringBefore("// 已发起但尚未回调")
        assertTrue(
            "ensureDaemonBound 命中「已连接」早返回时必须 pingDaemon()：" +
                "家长在 daemon 已启动之后才绑定设备时，这是触发 B→A 补建的唯一时机",
            ensure.contains("pingDaemon()")
        )
    }

    @Test
    fun `daemon 解绑自停必须先于心跳新鲜判定 否则留下僵尸通知`() {
        val daemon = readText(
            "src/main/kotlin/com/padguard/child/service/GuardDaemonService.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/service/GuardDaemonService.kt"
        )
        val loop = daemon.substringAfter("private suspend fun watchLoop() {")
            .substringBefore("\n    }")
            // 注释里原文引用了 `if (!stale) continue`（讲旧 bug），扫描必须只看代码行
            .lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        val unboundIdx = loop.indexOf("if (!GuardHeartbeat.readGuardState(this))")
        val freshIdx = loop.indexOf("if (!stale) {")
        assertTrue(
            "watchLoop 必须能看到解绑判定：设备解绑后 daemon 要主动 releaseGuard+stopSelf",
            unboundIdx >= 0
        )
        assertTrue(
            "解绑自停判定必须在 `if (!stale) continue` 之前：" +
                "否则解绑后主进程心跳仍新鲜时 daemon 永远走不到自停分支，" +
                "持有绑定导致主进程也杀不掉 —— 两条常驻通知的隐私事故",
            freshIdx >= 0 && unboundIdx >= 0 && unboundIdx < freshIdx
        )
        val destroy = daemon.substringAfter("override fun onDestroy() {").substringBefore("\n    }")
        assertTrue(
            "onDestroy 必须兜底 releaseGuard（系统回收 / 外部 stopService 的销毁路径）",
            destroy.contains("GuardLink.releaseGuard(this)")
        )
    }

    /**
     * 2026-11-17 三次复查（R53）：bind 优先引入的「半活态」回归。
     * 进程活着、binder 还连着、但服务内部循环卡住时：
     * - binder 死亡事件不会发生 → ensureBound 命中「已连接」直接返回 true；
     * - 旧逻辑 `if (!ensureBound) start` 因此永不 start → 心跳持续陈旧但无人恢复，
     *   整个守护链静默瘫痪。
     * 修：已连接也 ping（对端 MSG_WAKE 补心跳/重启循环），连续 2 拍陈旧再补 start 兜底。
     * 另：daemon 解绑自停后 watchJob 完成但引用非 null，重新绑定时必须重启循环。
     */
    @Test
    fun `心跳陈旧但 binder 仍连着必须 ping 加 start 兜底 防半活态`() {
        val link = readText(
            "src/main/kotlin/com/padguard/child/service/GuardLink.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/service/GuardLink.kt"
        )
        val daemon = readText(
            "src/main/kotlin/com/padguard/child/service/GuardDaemonService.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/service/GuardDaemonService.kt"
        )
        val guard = readText(
            "src/main/kotlin/com/padguard/child/service/GuardService.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/service/GuardService.kt"
        )
        assertTrue(
            "B 侧必须有 pingGuard()（已连接时唤醒主进程补心跳/重启主循环）",
            link.contains("fun pingGuard()")
        )
        assertTrue(
            "ensureGuardBound 命中已连接必须 pingGuard（半活态时 binder 不会死亡，" +
                "这是唯一即时唤醒通道）",
            link.substringAfter("fun ensureGuardBound(context: Context): Boolean = synchronized(stateLock) {")
                .substringBefore("if (guardBinding) return true").contains("pingGuard()")
        )
        assertTrue(
            "A 侧 pingDaemon 与 B 侧 pingGuard 必须对称存在",
            link.contains("private fun pingDaemon()")
        )
        // daemon 侧：陈旧拍数 + ping + 连续 2 拍补 start
        val loop = daemon.substringAfter("private suspend fun watchLoop() {")
            .substringBefore("\n    }")
        assertTrue(
            "daemon watchLoop 必须有连续陈旧计数（staleStreak），否则无法区分瞬时抖动与半活态",
            loop.contains("staleStreak++") && loop.contains("staleStreak = 0")
        )
        assertTrue(
            "陈旧分支必须 pingGuard + 连续 2 拍补 start：" +
                "仅靠 ensureGuardBound（已连接返回 true）会让半活态永久无人恢复",
            loop.contains("GuardLink.pingGuard(") && loop.contains("staleStreak >= 2")
        )
        // 主进程侧同理
        val startLoop = guard.substringAfter("var daemonStaleStreak = 0")
            .substringBefore("// 系统调度通道自愈")
        assertTrue(
            "GuardService 主循环必须有 daemonStaleStreak 且在连续陈旧 >=2 拍补 start：" +
                "daemon 半活态（进程在、循环死）时 binder 不死亡，ping 之外必须有 start 兜底",
            startLoop.contains("daemonStaleStreak++") &&
                startLoop.contains("GuardDaemonService.start(this@GuardService)") &&
                startLoop.contains("daemonStaleStreak >= 2")
        )
    }

    @Test
    fun `daemon 监视循环在解绑自停后重绑定时必须重启`() {
        val daemon = readText(
            "src/main/kotlin/com/padguard/child/service/GuardDaemonService.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/service/GuardDaemonService.kt"
        )
        val watching = daemon.substringAfter("private fun ensureWatching() {")
            .substringBefore("\n    }")
        assertTrue(
            "watchLoop 在解绑分支 return 后 Job 已完成但引用非 null：" +
                "ensureWatching 必须判断 !watchJob!!.isActive 并重启，" +
                "否则设备重新绑定后 daemon 永久失明（监视循环再也不跑）",
            watching.contains("watchJob == null || !watchJob!!.isActive")
        )
    }

    @Test
    fun `AB ping 不得每次重建 daemon 前台通知`() {
        val daemon = readText(
            "src/main/kotlin/com/padguard/child/service/GuardDaemonService.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/service/GuardDaemonService.kt"
        )
        assertTrue(
            "AB 锁已连接时主进程每 ~15s ping daemon 触发 ensureWatching，" +
                "前台化必须有幂等闸，否则每次重建 NotificationChannel + startForeground",
            daemon.contains("if (foregroundPromoted) return")
        )
        assertTrue(
            "前台化成功后必须置位（失败保留重试语义，不能提前置位）",
            daemon.contains("foregroundPromoted = true")
        )
        val destroy = daemon.substringAfter("override fun onDestroy() {").substringBefore("\n    }")
        assertTrue(
            "onDestroy 必须复位幂等闸：服务实例重建后 startForegroundService 的 5s 宽限重新计算",
            destroy.contains("foregroundPromoted = false")
        )
    }

    @Test
    fun `GuardService onDestroy 必须兜底释放 AB 绑定`() {
        val guard = readText(
            "src/main/kotlin/com/padguard/child/service/GuardService.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/service/GuardService.kt"
        )
        val destroy = guard.substringAfter("override fun onDestroy() {").substringBefore("\n    }")
        assertTrue(
            "GuardService.onDestroy 必须兜底 GuardLink.releaseAll(this)：" +
                "系统回收 / 外部 stopService 等非解绑流程的销毁同样要解绑，" +
                "否则 :daemon 因仍有绑定而无法销毁，僵尸服务 + 常驻通知",
            destroy.contains("GuardLink.releaseAll(this)")
        )
    }

    @Test
    fun `release 必须按 bind 计数解干净 否则留下杀不掉的服务`() {
        val link = readText(
            "src/main/kotlin/com/padguard/child/service/GuardLink.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/service/GuardLink.kt"
        )
        assertTrue(
            "releaseDaemon/releaseGuard 必须循环解绑到计数清零（while count > 0），" +
                "只解一层会留下多余绑定 —— stopSelf 不生效、常驻通知杀不掉",
            link.contains("while (daemonBindCount > 0") && link.contains("while (guardBindCount > 0")
        )
        assertTrue(
            "解绑循环必须有上限防死循环（计数异常时不能把 release 路径挂死）",
            link.contains("MAX_UNBIND_ROUNDS")
        )
        assertTrue(
            "每次成功 bindService 都必须累加计数（漏加会让 release 解不干净账实不符）",
            link.contains("daemonBindCount++") && link.contains("guardBindCount++")
        )
    }


    /**
     * 2026-11-18 R54：绑定状态机并发原子化。
     * ensureDaemonBound 的调用方横跨主线程（onCreate）与 Dispatchers.Default（心跳循环），
     * ServiceConnection 回调在主线程 —— check-then-act（`if (binding) return` + `binding=true`）
     * 若无互斥，两个线程可同时通过检查各自 bindService，bind 计数叠加 → 解绑后 daemon 杀不掉。
     * 此类纯接线代码无 androidTest 可行为化，沿用源码锁。
     */
    @Test
    fun `绑定状态机必须统一持锁 禁止裸 check-then-act`() {
        val link = readText(
            "src/main/kotlin/com/padguard/child/service/GuardLink.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/service/GuardLink.kt"
        )
        assertTrue(
            "两侧 ensure 必须在 stateLock 内执行（锁缺失 = 并发双 bind = 计数叠加隐私事故）",
            link.contains("fun ensureDaemonBound(context: Context): Boolean = synchronized(stateLock)") &&
                link.contains("fun ensureGuardBound(context: Context): Boolean = synchronized(stateLock)")
        )
        // 两侧断连回调（onLost）与计数解绑同样必须持锁
        assertTrue(
            link.contains("private fun onDaemonLost(app: Context, why: String, needUnbind: Boolean) = synchronized(stateLock)") &&
                link.contains("private fun onGuardLost(app: Context, why: String, needUnbind: Boolean) = synchronized(stateLock)") &&
                link.contains("fun releaseDaemon(context: Context) = synchronized(stateLock)") &&
                link.contains("fun releaseGuard(context: Context) = synchronized(stateLock)")
        )
    }


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
