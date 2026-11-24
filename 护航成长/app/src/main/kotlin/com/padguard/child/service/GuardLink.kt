package com.padguard.child.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.padguard.core.common.Logger

/**
 * AB 锁：主进程（A）与守护进程（B）以 binder **互相绑定**，任一方死亡由另一方拉回。
 *
 * ## 为什么 `startForegroundService` 不够
 * 现有的三条自恢复通道（JobScheduler 看门狗、AlarmManager 闹钟、daemon 心跳监视）最终都调
 * [GuardService.start] → `startForegroundService`。这条路有两个硬伤：
 * 1. **API 31+ 后台启动前台服务被禁止**：从 BroadcastReceiver / JobService 回调里启动会抛
 *    `ForegroundServiceStartNotAllowedException`，代码里只能 `runCatching` 记一条日志 ——
 *    外部看到的就是"清理完再也没起来"。本项目目标机虽为 API 29，但升级系统或换机即暴露。
 * 2. **国产 ROM 的启动拦截**：EMUI `iawise` 会把"由广播/Job 触发的服务启动"判为自启，
 *    直接拒绝且**无异常无回调**（[GuardDaemonService] 注释里记录过一次实测）。
 *
 * `bindService(BIND_AUTO_CREATE)` 走的是完全不同的路径：它是**建立进程间绑定**，不是"启动服务"，
 * 因此不受上述两条限制；目标进程不存在时系统会**创建进程**并在其中构造 Service
 * （onCreate → onBind），随后由绑定关系维持其 adj（`service` 级，比 cached 更抗内存回收）。
 * 这正是「一键清理把两个进程都杀掉之后，还能被系统侧通道重新拉起来」的那条缝。
 *
 * ## 为什么必须双向
 * 单向只能"B 拉 A"。A 被单独杀掉时 B 拉得回来；但 B 被单独杀掉（内存回收、用户长按停止、
 * 系统更新 FGS 配额）时，A 若不会拉 B，就退化成单点 —— 下一次 A 死掉时无人兜底。
 * 双向绑定后每一方都是对方的看门人，且 binder 死亡是**即时事件**（不必等 5s 心跳轮询）。
 *
 * ## 生命周期约束（改动前必读）
 * 绑定会让目标服务**无法被销毁**（`stopSelf` 在有绑定的服务上不生效）。因此：
 * - 只有在「已绑定设备」（`guard_state=bound`）时才建立/维持绑定；
 * - 解绑 / 退出管控时必须调 [releaseAll] 解除双向绑定，否则会留下两个杀不掉的服务和两条
 *   常驻通知 —— 那是隐私事故，不是保活。
 *
 * ## 重绑退避
 * 目标进程持续崩溃时不能热循环：1s → 2s → 4s … 最多 [MAX_BACKOFF_MS]。连通成功后复位。
 * 退避任务挂在主线程 Handler 上，进程被杀即自然消失（此时由对方的 bind 反向拉起）。
 *
 * ## 加固三项（2026-11-17，针对真实故障模式）
 * 1. **onServiceDisconnected 也先 unbind 再重绑**：`BIND_AUTO_CREATE` 下进程死亡后系统会
 *    自动重启服务并在**同一条 ServiceConnection** 上重连；旧实现只重绑不解除，每次断连
 *    都会叠加一层 bind 计数 —— [releaseAll] 只解一层，累积后解绑设备时目标服务**永远
 *    杀不掉**（常驻通知泄漏）。现在断连统一走「解绑→退避重绑」，任何时刻同侧只有一条绑定。
 * 2. **绑定发起超时**：`bindService` 返回 true 不代表绑定会建立 —— 目标进程 onCreate 崩溃
 *    或 ROM 静默吞掉重启时**永远没有回调**，`binding=true` 会永久挂死，所有调用方误判
 *    "已处理"，start 兜底与重绑都不再触发（外部表现即"清理后永久脱管"）。
 *    [BIND_TIMEOUT_MS] 后仍无回调即按失败处理：解绑、复位、按退避重试。
 * 3. **按计数解绑**：并发/竞态下同侧可能发生多次 bind（标志位非原子），release 时把
 *    计数清到 0 为止，保证解绑路径永远走得完。
 */
object GuardLink {

    private const val TAG = "GuardLink"

    /** 唤醒消息：服务收到后确认自己是"被 binder 唤起"的，需要把主干（前台/主循环）拉起来 */
    const val MSG_WAKE = 1

    private const val MIN_BACKOFF_MS = 1_000L
    private const val MAX_BACKOFF_MS = 30_000L

    /**
     * 绑定发起超时：bindService 已接受但 onServiceConnected / onNullBinding / 死亡回调
     * 一个都没到（目标 onCreate 崩溃 / ROM 静默拦截），视为本次尝试失败，解绑重试。
     */
    private const val BIND_TIMEOUT_MS = 30_000L

    /** 解绑计数上限（防计数异常时的无限循环；正常状态单侧不会超过个位数） */
    private const val MAX_UNBIND_ROUNDS = 16

    private val main = Handler(Looper.getMainLooper())

    /**
     * 绑定状态机互斥锁（2026-11-18 R54）。
     *
     * 此前 daemon* / guard* 标志虽是 @Volatile，但 `if (bound) / if (binding) / binding=true`
     * 这类 check-then-act 序列**非原子**：`ensureDaemonBound` 既被主线程调用（GuardService.onCreate），
     * 也被 Dispatchers.Default 线程调用（主循环心跳陈旧分支），而 ServiceConnection 回调在主线程 ——
     * 并发下两个线程可同时通过 `if (daemonBinding)` 检查，各自 bindService 一次，
     * bind 计数叠加 → releaseAll 解不干净 → 解绑设备后 daemon 杀不掉（类注释里的"隐私事故"）。
     * 加固 3（按计数解绑）是兜底，这里是根因修复：所有状态读改写收敛到同一把锁。
     *
     * 锁内调用 bindService/unbindService/Messenger.send 均为异步短 IPC（<5ms），不会长占；
     * 回调是异步投递，不存在"持锁等待回调"的死锁路径。synchronized 可重入，函数间互调安全。
     */
    private val stateLock = Any()

    // ==================== A 侧：主进程 → :daemon ====================

    @Volatile
    private var daemonBound = false

    @Volatile
    private var daemonBinding = false

    @Volatile
    private var daemonWanted = false

    @Volatile
    private var daemonBackoffMs = MIN_BACKOFF_MS

    /** 同一 ServiceConnection 上活跃的 bind 次数（bind 计数 - unbind 计数） */
    @Volatile
    private var daemonBindCount = 0

    private var daemonConn: ServiceConnection? = null

    /** 已连接时持有的 daemon binder：用于「已绑定再 ping」触发 daemon 侧补建 B→A 绑定 */
    @Volatile
    private var daemonMessenger: Messenger? = null

    private var daemonTimeoutTask: Runnable? = null

    private var daemonRebindTask: Runnable? = null

    /**
     * 确保主进程与 `:daemon` 处于绑定态（幂等）。
     *
     * @return true = 绑定已就绪或本次已发起（调用方无需再 start 兜底）；
     *         false = **绑定请求被系统拒绝**，调用方应退回 `startForegroundService`。
     */
    fun ensureDaemonBound(context: Context): Boolean = synchronized(stateLock) {
        val app = context.applicationContext
        daemonWanted = true
        if (daemonBound) {
            // 已连上也要 ping 一次：daemon 可能是在「设备尚未绑定家长」时创建的，
            // 那时它 onCreate 里 readGuardState=false 跳过了 B→A 绑定；家长绑定后
            // 主进程再调本方法只会命中这个早返回。ping 会触发 daemon 的
            // ensureWatching() → ensureGuardBound()，把 AB 锁补成真正的双向
            // （否则主进程被杀时 daemon 没有 binder 即时死亡事件，退化成 5s 心跳轮询）。
            pingDaemon()
            return true
        }
        // 已发起但尚未回调 onServiceConnected：仍在建立中，同样视为"已处理"，
        // 否则主循环每 15s 都会因为"还没连上"再补一次 start，白起一条 FGS 通知。
        // 注意有 [BIND_TIMEOUT_MS] 超时兜底：卡死在这一态（ROM 静默拦截）不会永久锁死。
        if (daemonBinding) return true
        daemonBinding = true

        val conn = daemonConn ?: object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                synchronized(stateLock) {
                    daemonBound = true
                    daemonBinding = false
                    daemonBackoffMs = MIN_BACKOFF_MS
                    daemonMessenger = Messenger(service)
                    cancelDaemonBindTimeout()
                    Logger.i(TAG) { "AB: bound to :daemon (count=$daemonBindCount)" }
                    // 反向通知：让 daemon 确认自己是被 binder 唤起，需要把监视循环拉起来
                    pingDaemon()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // 加固 1：必须先 unbind 再重绑。系统对 BIND_AUTO_CREATE 的自动重启会复用
                // 同一条连接重连，若只重绑不解绑，每断连一次就叠加一层 bind 计数，
                // releaseAll 解不干净 → 解绑设备后 daemon 杀不掉（常驻通知泄漏）。
                onDaemonLost(app, "disconnected", needUnbind = true)
            }

            override fun onBindingDied(name: ComponentName?) {
                // binder 已死：必须 unbind 之后重新 bind，否则系统会复用这条死连接
                onDaemonLost(app, "bindingDied", needUnbind = true)
            }

            override fun onNullBinding(name: ComponentName?) {
                onDaemonLost(app, "nullBinding", needUnbind = true)
            }
        }.also { daemonConn = it }

        val accepted = runCatching {
            app.bindService(Intent(app, GuardDaemonService::class.java), conn, Context.BIND_AUTO_CREATE)
        }.getOrElse {
            Logger.e(TAG, it) { "AB: bindService(:daemon) threw" }
            false
        }
        if (!accepted) {
            daemonBinding = false
            scheduleDaemonRebind(app)
            return false
        }
        // 加固 2：接受 ≠ 建立。回调迟迟不来（目标 onCreate 崩溃 / ROM 拦截重启）
        // 时按失败处理，否则 binding 永久挂死、所有兜底通道全部哑火。
        daemonBindCount++
        scheduleDaemonBindTimeout(app)
        return true
    }

    /** 解除与 `:daemon` 的绑定（解绑设备时调用，否则 daemon 无法销毁） */
    fun releaseDaemon(context: Context) = synchronized(stateLock) {
        daemonWanted = false
        daemonBound = false
        daemonBinding = false
        daemonBackoffMs = MIN_BACKOFF_MS
        cancelDaemonBindTimeout()
        daemonRebindTask?.let { main.removeCallbacks(it) }
        daemonRebindTask = null
        // 加固 3：按计数清干净。只解一层时，历史叠加的多余绑定会让 daemon 在解绑后
        // 依然杀不掉（有绑定 stopSelf 不生效）—— 这是隐私事故不是保活。
        var rounds = 0
        while (daemonBindCount > 0 && rounds < MAX_UNBIND_ROUNDS) {
            unbindDaemonOnce(context)
            rounds++
        }
        if (daemonBindCount > 0) {
            Logger.e(TAG) { "AB: daemon bind count still $daemonBindCount after $rounds unbind rounds" }
        }
        daemonMessenger = null
        daemonConn = null
    }

    /** 向已连接的 daemon 发一次 WAKE：幂等补建 B→A 绑定 + 确保监视循环在跑 */
    private fun pingDaemon(): Unit = synchronized(stateLock) {
        val peer = daemonMessenger ?: return
        runCatching { peer.send(Message.obtain(null, MSG_WAKE)) }
            .onFailure { Logger.w(TAG, it) { "AB: ping :daemon failed" } }
    }

    private fun unbindDaemonOnce(context: Context): Unit = synchronized(stateLock) {
        val conn = daemonConn ?: return
        if (daemonBindCount <= 0) return
        // 抛"not bound"说明系统侧绑定早已消失，计数照减保持账实一致。
        runCatching { context.applicationContext.unbindService(conn) }
            .onFailure { Logger.w(TAG, it) { "AB: unbind(:daemon) failed (already gone?)" } }
        daemonBindCount--
    }

    private fun onDaemonLost(app: Context, why: String, needUnbind: Boolean) = synchronized(stateLock) {
        daemonBound = false
        daemonBinding = false
        daemonMessenger = null
        cancelDaemonBindTimeout()
        Logger.w(TAG) { "AB: lost :daemon ($why, count=$daemonBindCount)" }
        if (needUnbind) {
            unbindDaemonOnce(app)
        }
        if (daemonWanted) scheduleDaemonRebind(app)
    }

    /** 绑定发起超时任务：接受后 [BIND_TIMEOUT_MS] 仍无任何回调即判失败并重试 */
    private fun scheduleDaemonBindTimeout(app: Context) = synchronized(stateLock) {
        cancelDaemonBindTimeout()
        val task = Runnable {
            daemonTimeoutTask = null
            if (daemonWanted && daemonBinding && !daemonBound) {
                Logger.w(TAG) {
                    "AB: bind(:daemon) accepted but no callback in ${BIND_TIMEOUT_MS / 1000}s " +
                        "(target crashed in onCreate / ROM silent block) -> treat as failure"
                }
                unbindDaemonOnce(app)
                daemonBinding = false
                scheduleDaemonRebind(app)
            }
        }
        daemonTimeoutTask = task
        main.postDelayed(task, BIND_TIMEOUT_MS)
    }

    private fun cancelDaemonBindTimeout() {
        daemonTimeoutTask?.let { main.removeCallbacks(it) }
        daemonTimeoutTask = null
    }

    private fun scheduleDaemonRebind(app: Context): Unit = synchronized(stateLock) {
        if (!daemonWanted) return
        val delay = daemonBackoffMs
        daemonBackoffMs = (daemonBackoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        val task = Runnable {
            daemonRebindTask = null
            if (daemonWanted && !daemonBound && !daemonBinding) {
                // 重新 bind 即会重建 :daemon 进程（与它为什么死无关：内存回收 / 被清理 / 崩溃）
                ensureDaemonBound(app)
            }
        }
        daemonRebindTask?.let { main.removeCallbacks(it) }
        daemonRebindTask = task
        main.postDelayed(task, delay)
    }

    // ==================== B 侧：:daemon → 主进程 ====================

    @Volatile
    private var guardBound = false

    @Volatile
    private var guardBinding = false

    @Volatile
    private var guardWanted = false

    @Volatile
    private var guardBackoffMs = MIN_BACKOFF_MS

    /** 同一 ServiceConnection 上活跃的 bind 次数（bind 计数 - unbind 计数） */
    @Volatile
    private var guardBindCount = 0

    private var guardConn: ServiceConnection? = null

    /** 已连接时持有的主进程 binder：与 daemonMessenger 对称，用于「已连上但主循环卡住」时的即时唤醒 */
    @Volatile
    private var guardMessenger: Messenger? = null

    private var guardTimeoutTask: Runnable? = null

    private var guardRebindTask: Runnable? = null

    /**
     * 确保 `:daemon` 与主进程处于绑定态（幂等）。
     *
     * 这是"B 拉 A"的那一半：daemon 存活时主进程被杀，绑定立即断（binder 死亡是即时的），
     * 重绑会**创建主进程**并触发 [GuardService.onBind] → `bootstrap`。
     *
     * @return true = 绑定已就绪或本次已发起；false = 绑定请求被系统拒绝（调用方退回 start）
     */
    fun ensureGuardBound(context: Context): Boolean = synchronized(stateLock) {
        val app = context.applicationContext
        guardWanted = true
        if (guardBound) {
            // 与 A 侧对称：已连接也要 ping。binder 还在 ≠ 主循环在跑（心跳陈旧但进程活），
            // binder 死亡事件此时不会发生，ping 是唤醒对端补写心跳/重启主循环的唯一即时通道。
            pingGuard()
            return true
        }
        if (guardBinding) return true
        guardBinding = true

        val conn = guardConn ?: object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                synchronized(stateLock) {
                    guardBound = true
                    guardBinding = false
                    guardBackoffMs = MIN_BACKOFF_MS
                    guardMessenger = Messenger(service)
                    cancelGuardBindTimeout()
                    Logger.i(TAG) { "AB: :daemon bound to guard (count=$guardBindCount)" }
                    pingGuard()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // 加固 1：与 A 侧同理，先 unbind 再重绑，防止 bind 计数叠加。
                onGuardLost(app, "disconnected", needUnbind = true)
            }

            override fun onBindingDied(name: ComponentName?) {
                onGuardLost(app, "bindingDied", needUnbind = true)
            }

            override fun onNullBinding(name: ComponentName?) {
                onGuardLost(app, "nullBinding", needUnbind = true)
            }
        }.also { guardConn = it }

        val accepted = runCatching {
            app.bindService(Intent(app, GuardService::class.java), conn, Context.BIND_AUTO_CREATE)
        }.getOrElse {
            Logger.e(TAG, it) { "AB: bindService(guard) threw" }
            false
        }
        if (!accepted) {
            guardBinding = false
            scheduleGuardRebind(app)
            return false
        }
        // 加固 2：与 A 侧同理，接受 ≠ 建立，超时兜底。
        guardBindCount++
        scheduleGuardBindTimeout(app)
        return true
    }

    /** 解除 daemon 对主进程的绑定（daemon 判定未绑定设备、准备自停时调用） */
    fun releaseGuard(context: Context) = synchronized(stateLock) {
        guardWanted = false
        guardBound = false
        guardBinding = false
        guardBackoffMs = MIN_BACKOFF_MS
        cancelGuardBindTimeout()
        guardRebindTask?.let { main.removeCallbacks(it) }
        guardRebindTask = null
        // 加固 3：按计数清干净，保证 daemon 自停前把对主进程的绑定全部解除。
        var rounds = 0
        while (guardBindCount > 0 && rounds < MAX_UNBIND_ROUNDS) {
            unbindGuardOnce(context)
            rounds++
        }
        if (guardBindCount > 0) {
            Logger.e(TAG) { "AB: guard bind count still $guardBindCount after $rounds unbind rounds" }
        }
        guardMessenger = null
        guardConn = null
    }

    /** 向已连接的主进程发一次 WAKE：补写心跳 + 确保主循环在跑（半活态自救） */
    fun pingGuard(): Unit = synchronized(stateLock) {
        val peer = guardMessenger ?: return
        runCatching { peer.send(Message.obtain(null, MSG_WAKE)) }
            .onFailure { Logger.w(TAG, it) { "AB: ping guard failed" } }
    }

    private fun unbindGuardOnce(context: Context): Unit = synchronized(stateLock) {
        val conn = guardConn ?: return
        if (guardBindCount <= 0) return
        runCatching { context.applicationContext.unbindService(conn) }
            .onFailure { Logger.w(TAG, it) { "AB: unbind(guard) failed (already gone?)" } }
        guardBindCount--
    }

    private fun onGuardLost(app: Context, why: String, needUnbind: Boolean) = synchronized(stateLock) {
        guardBound = false
        guardBinding = false
        guardMessenger = null
        cancelGuardBindTimeout()
        Logger.w(TAG) { "AB: lost guard ($why, count=$guardBindCount)" }
        if (needUnbind) {
            unbindGuardOnce(app)
        }
        if (guardWanted) scheduleGuardRebind(app)
    }

    private fun scheduleGuardBindTimeout(app: Context) = synchronized(stateLock) {
        cancelGuardBindTimeout()
        val task = Runnable {
            guardTimeoutTask = null
            if (guardWanted && guardBinding && !guardBound) {
                Logger.w(TAG) {
                    "AB: bind(guard) accepted but no callback in ${BIND_TIMEOUT_MS / 1000}s " +
                        "(target crashed in onCreate / ROM silent block) -> treat as failure"
                }
                unbindGuardOnce(app)
                guardBinding = false
                scheduleGuardRebind(app)
            }
        }
        guardTimeoutTask = task
        main.postDelayed(task, BIND_TIMEOUT_MS)
    }

    private fun cancelGuardBindTimeout() {
        guardTimeoutTask?.let { main.removeCallbacks(it) }
        guardTimeoutTask = null
    }

    private fun scheduleGuardRebind(app: Context): Unit = synchronized(stateLock) {
        if (!guardWanted) return
        val delay = guardBackoffMs
        guardBackoffMs = (guardBackoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        val task = Runnable {
            guardRebindTask = null
            if (guardWanted && !guardBound && !guardBinding) {
                ensureGuardBound(app)
            }
        }
        guardRebindTask?.let { main.removeCallbacks(it) }
        guardRebindTask = task
        main.postDelayed(task, delay)
    }

    // ==================== 系统通道入口 ====================

    /**
     * 系统通道（看门狗 Job / 闹钟广播）专用：把主进程拉回来。
     *
     * 调用方**就在主进程内**（JobService / BroadcastReceiver 默认进程），因此这里绑定的是
     * 自己进程的服务 —— 但走 bind 而非 `startForegroundService`，绕开后台启动 FGS 限制。
     * 返回 false 时调用方再用 `start` 兜底（绑定被系统拒绝的异常场景）。
     */
    fun wakeGuard(context: Context): Boolean = ensureGuardBound(context)

    /**
     * 系统通道专用：把 `:daemon` 拉回来。
     *
     * 与 [wakeGuard] 同理，但目标是另一个进程 —— 由 system_server 持有回调时，
     * bind 会真的把 `:daemon` 进程创建出来。
     */
    fun wakeDaemon(context: Context): Boolean = ensureDaemonBound(context)

    /** 解绑设备 / 退出管控：解除双向绑定（见类注释「生命周期约束」） */
    fun releaseAll(context: Context) {
        Logger.i(TAG) { "AB: release all bindings (unbound)" }
        releaseDaemon(context)
        releaseGuard(context)
    }
}
