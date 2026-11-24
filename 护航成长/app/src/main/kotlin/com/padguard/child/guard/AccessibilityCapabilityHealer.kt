package com.padguard.child.guard

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import com.padguard.child.capture.AccessibilityScreenshot
import com.padguard.core.common.Logger
import com.padguard.core.engine.admin.DeviceAdminBridge
import com.padguard.core.engine.admin.OpResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 无障碍截屏能力自愈 —— 修复"服务启用早于 canTakeScreenshot 能力"的陈旧元数据。
 *
 * ## 解决什么问题
 * `canTakeScreenshot`（远程截屏/实时看屏无障碍通道的前提）来自无障碍服务元数据。
 * 系统只在服务被**启用**的那一刻读取一次元数据；孩子端从"无此能力的旧版本"覆盖升级后，
 * 服务仍是启用态但能力位不在 —— 截屏/实时看屏被迫退回 MediaProjection 投影链路：
 * 状态栏出现"正在共享屏幕"红标、必要时还要向孩子弹授权框。手动修复需要用户在
 * 系统设置里把服务关闭再打开，但无障碍入口藏得深，家长基本找不到。
 *
 * ## 自愈原理
 * 具备静默写 secure 设置能力的设备可把服务**先禁用再启用**一次，系统会以新版本元数据
 * 重新绑定 —— 能力位随重读生效。全程无界面、无弹窗，孩子无感知（约 3 秒内完成，
 * 期间拦截闸门短暂离线后自动恢复）。
 *
 * ## 静默写入的两条通道（见 [canSilentHeal]）
 * - Device Owner：`DevicePolicyManager.setSecureSetting`（原通道）；
 * - 装机时经 adb 一次性授权的 `WRITE_SECURE_SETTINGS`（2026-11-17 新增）：
 *   让无 DO 的华为平板在「一键清理」停用无障碍后也能**全自动恢复**，
 *   而不是只能检测+告警+等家长到场。未授权设备本类自动退回旧行为。
 *
 * ## 触发纪律（防循环）
 * - 仅当：API 30+ 且服务能力缺失（[AccessibilityScreenshot.isAvailable] 为 false）；
 * - 每个进程生命周期至多尝试一次（内存标记）；成功后能力就位，天然不会再进本分支；
 * - 设备既无 DO 也无 WRITE_SECURE_SETTINGS 授权时无法静默写 secure 设置，只记日志给
 *   运维指引（手动开关或卸载重装——卸载会清掉服务启用态，重装后首次启用即读取新
 *   元数据，同样能治愈）。
 */
@Singleton
class AccessibilityCapabilityHealer @Inject constructor(
    private val admin: DeviceAdminBridge,
    private val accessibilityScreenshot: AccessibilityScreenshot,
    @ApplicationContext private val context: Context
) {

    /** 本进程是否已尝试过自愈（成功与失败都算：失败留待下次进程重启再试，防抖动循环） */
    @Volatile private var attemptedThisProcess = false

    /** 上次强制重绑时刻，用于限频（见 [rebindIfDetached]） */
    @Volatile private var lastRebindAt = 0L

    private val component = ComponentName(context, OperationGuardAccessibilityService::class.java)
    private val flat = component.flattenToString()

    /**
     * 串行化所有对 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 的「读-改-写」。
     * [healIfNeeded] / [rebindIfDetached] / [ensureEnabledInternal] / [disable] 都跑在
     * Dispatchers.Default（多线程），各自「读名单→改→延迟→写回」若不串行，彼此的移除/恢复会交错；
     * 更严重的是「恢复写」用的是延迟前捕获的旧快照，会把并发 disable（家长解绑）的改动整体覆盖，
     * 甚至在解绑过程中把本服务重新加回启用名单（权限放大）。统一加锁 + 恢复前重读可彻底消除。
     */
    private val opMutex = Mutex()

    // ==================== 无障碍开关「秒级」自愈监听（Settings.Secure ContentObserver） ====================

    /**
     * 监听系统无障碍设置被**外部**改动的观察器。
     *
     * ## 为什么必须有这一层（2026-11-17 EMUI 一键清理事故）
     * 此前 a11y 恢复只靠 [PermissionHealthChecker] 的 5 分钟周期自检 + 服务启动，
     * 清理掉无障碍开关后要等 1~3 分钟才补写回 —— 这段时间闸门裸奔。
     * 本观察器直接订阅 `Settings.Secure` 的两把键，ROM 一旦把本服务移出启用名单
     * 或把 `ACCESSIBILITY_ENABLED` 总开关置 0，回调在**毫秒级**触发 [onA11ySettingsChanged]，
     * 只读判定确实被关后立刻静默写回，把恢复窗口从分钟压到秒级。
     *
     * ## 四层防自激/误触设计（缺一不可，否则会陷入死循环或在解绑后"复活"管控）
     * 1. **主控开关闸** [shouldHeal]：回调先看整机管控总开关（GuardMasterSwitch）是否仍 active。
     *    家长解绑时先把总开关置 false 再 disable()；没有这道闸，disable() 的写会触发本观察器，
     *    又把刚解绑的服务重新启用 —— 设备"解绑了却仍被拦截"。这是最关键的防线。
     * 2. **回声抑制窗** [writeEchoUntil]：所有对 Settings.Secure 的写都经 [writeSecure] 统一入口，
     *    每写一次就压一个短窗口。这挡住两类自激：① 写回→回调的异步回声；② rebind/healIfNeeded
     *    刻意的"摘除→延迟→恢复"中途那 1.5s 名单里没有我们，若无抑制会被当外部清理
     *    触发并发 heal，与刻意的重绑互相打架。
     * 3. **只读判定**：回调先读当前设置，[needsReenable] 为 false（健康）直接返回，绝不写。
     * 4. **抖动合并**：ROM 一次清理常连改多把键，debounce 合并为一次判定。
     *
     * ## 生命周期
     * 由抗杀的 `GuardService.onCreate` 注册、`onDestroy` 注销，随服务重建自动重新订阅。
     * 进程被 force-stop（STOPPED 态）时观察器随进程消亡 —— 这是 Android 物理上限，
     * 由 AB 双进程 + 看门狗 + 开机广播拉回进程，拉回后 onCreate 重新注册即恢复监听。
     */
    @Volatile private var watcherActive = false
    private var watcherThread: HandlerThread? = null
    private var watcher: ContentObserver? = null
    private var debounceJob: Job? = null

    /**
     * "当前是否仍在管控"的权威探针，由 GuardService 传入（读 GuardMasterSwitch.active）。
     * 解绑/退出管控过程中为 false，本观察器一律不写 —— 防止把解绑时主动禁用的服务重新拉起。
     * 类型用函数而非直接持有 GuardMasterSwitch，避免能力类反向依赖 UI/服务层。
     */
    @Volatile private var shouldHeal: (() -> Boolean)? = null

    /** 自己写 Settings.Secure 后的异步回声抑制截止时刻（elapsedRealtime 单调钟）。 */
    @Volatile private var writeEchoUntil = 0L

    /**
     * 注册无障碍设置观察者。幂等（重复调用直接返回）。
     * 即便当前无静默写入通道也允许注册 —— 回调里会再次判定，设备后续被授权即可立即生效。
     *
     * @param scope 观察者挂靠的协程作用域（用 GuardService 的服务级 scope，随服务销毁）。
     * @param shouldHeal "仍在管控"探针；返回 false 时回调一律不写（解绑防复活）。
     */
    fun startWatching(scope: CoroutineScope, shouldHeal: () -> Boolean) {
        if (watcherActive) return
        watcherActive = true
        this.shouldHeal = shouldHeal
        val thread = HandlerThread("a11y-settings-watch").apply { start() }
        watcherThread = thread
        val handler = Handler(thread.looper)
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = onA11ySettingsChanged(scope)
            override fun onChange(selfChange: Boolean, uri: Uri?) = onA11ySettingsChanged(scope)
        }
        watcher = observer
        val resolver = context.contentResolver
        runCatching {
            // 订阅父 URI + descendants：标准 Android 会通知 per-key URI，但部分 ROM
            // （含 EMUI 定制 SettingsProvider）只通知 content://settings/secure 父节点。
            // 父节点方案覆盖两者，回调频率虽高但被 gate/debounce/只读判定三重挡住，开销可忽略。
            resolver.registerContentObserver(Settings.Secure.CONTENT_URI, true, observer)
            Logger.i(TAG) { "a11y settings observer registered (instant heal on external disable)" }
        }.onFailure { Logger.w(TAG, it) { "register a11y settings observer failed" } }
    }

    /** 注销观察者，服务销毁时调用；幂等。 */
    fun stopWatching() {
        if (!watcherActive) return
        watcherActive = false
        shouldHeal = null
        debounceJob?.cancel()
        debounceJob = null
        watcher?.let { obs ->
            runCatching { context.contentResolver.unregisterContentObserver(obs) }
                .onFailure { Logger.w(TAG, it) { "unregister a11y settings observer failed" } }
        }
        watcher = null
        runCatching { watcherThread?.quit() }
        watcherThread = null
    }

    /** 设置被改动时的回调：抖动合并 → 主控闸 → 只读判定 → 需要则静默写回。 */
    private fun onA11ySettingsChanged(scope: CoroutineScope) {
        if (!watcherActive) return
        // 回声抑制：自己（或 rebind 的中途摘除）写设置引发的回调直接丢弃
        if (SystemClock.elapsedRealtime() < writeEchoUntil) return
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(WATCH_DEBOUNCE_MS)
            if (!watcherActive) return@launch
            // 主控闸：解绑/退出管控后绝不写，防"复活"（最关键的一道）
            if (shouldHeal?.invoke() != true) {
                Logger.i(TAG) { "a11y settings changed but control inactive; skip heal" }
                return@launch
            }
            if (!canSilentHeal()) return@launch
            val listRaw = runCatching {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            }.getOrNull()
            val master = runCatching {
                Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }.getOrDefault(1)
            if (!needsReenable(listRaw, master, flat)) {
                Logger.i(TAG) { "a11y settings changed but service healthy; skip heal" }
                return@launch
            }
            Logger.w(TAG) {
                "a11y disabled externally detected (master=$master, list contains ours=" +
                    "${listRaw.orEmpty().contains(flat, ignoreCase = true)}); instant silent re-enable"
            }
            runCatching { ensureEnabled() }
                .onFailure { Logger.w(TAG, it) { "instant a11y heal on settings change failed" } }
        }
    }

    /**
     * 本设备是否**真的**具备「静默改写无障碍启用名单」的能力。
     *
     * ## 2026-11-18 修正：DO 不再是可用通道（真机实证）
     * 旧实现对 Device Owner 无条件返回 true，理由是"DO API 可以写 secure 设置"。
     * 实测（华为 AGS6-W10 / EMUI 10，已设 DO）：
     * ```
     * SecurityException: Permission denial: Device owners cannot update accessibility_enabled
     * SecurityException: Permission denial: Device owners cannot update enabled_accessibility_services
     * ```
     * Android 10 起 DO 的 secure-setting 白名单**明确排除无障碍这两把键**。
     * 旧返回值带来的后果比"自愈失败"更糟：调用方（[PermissionHealthChecker]）看到
     * "有自愈通道"就不发告警，于是无障碍真被关掉时**既不修也不报**，家长端一片安静。
     *
     * 所以现在只认真正可用的通道：
     * - `WRITE_SECURE_SETTINGS`（装机 `adb shell pm grant` 一次性授权）→ 直写 `Settings.Secure`，
     *   不受 DO 白名单限制，这是唯一可靠通道；
     * - Android 10 以下的 DO（旧白名单未收紧）保留兼容。
     *
     * 未具备通道时返回 false，所有调用方自动退回「检测+告警+引导人工重开」，绝不假装能自愈。
     */
    fun canSilentHeal(): Boolean =
        context.checkSelfPermission(PERMISSION_WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED ||
            (admin.isDeviceOwner() && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)

    /**
     * 统一的 secure 设置写入口。
     *
     * ## 通道优先级（2026-11-18 修正，真机事故根因）
     * **直写 `Settings.Secure` 优先，DO API 兜底** —— 与旧实现正好相反。
     *
     * 旧实现 `if (admin.isDeviceOwner()) return admin.setSecureSetting(...)` 是提前返回：
     * 设备一旦设成 DO，就再也不会尝试 `WRITE_SECURE_SETTINGS` 直写。而 Android 10 起
     * `DevicePolicyManager.setSecureSetting` 对 `accessibility_enabled` /
     * `enabled_accessibility_services` 会直接抛
     * `SecurityException: Device owners cannot update ...`。
     * 结果是：**设 DO 反而把原本可用的静默自愈通道锁死**，一键清理后无障碍再也回不来，
     * 看屏 / 截屏 / 锁屏看门狗（都依赖无障碍）全线降级，而日志还写着"自愈成功"。
     *
     * 直写需要 `WRITE_SECURE_SETTINGS`（signature|privileged，装机时 `pm grant` 一次性授予），
     * 它走的是 `SettingsProvider` 而不是 DPM，**不受 DO 的无障碍白名单限制** —— 这才是可靠通道。
     * 只有拿不到该权限、且确实是 DO 时，才退回 DPM（此时无障碍键大概率仍失败，
     * 但其它键与非无障碍场景仍可能可用）。
     *
     * @return 写入结果。调用方**必须**检查返回值，不得假定成功（见 [ensureEnabledLocked]）。
     */
    private fun writeSecure(key: String, value: String): OpResult {
        // 任何一次对无障碍两把键的写都压一段回声抑制窗：本类所有写（enable/disable/rebind/
        // healIfNeeded）都经此单一入口，避免各自忘记设窗导致观察器把"自己的写"当外部清理。
        if (key == Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES ||
            key == Settings.Secure.ACCESSIBILITY_ENABLED
        ) {
            writeEchoUntil = SystemClock.elapsedRealtime() + WRITE_ECHO_WINDOW_MS
        }

        val hasDirectWrite = context.checkSelfPermission(PERMISSION_WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

        if (hasDirectWrite) {
            val direct = putSecureDirect(key, value)
            if (direct.isOk) return direct
            // 直写被 ROM 拒绝时，若还有 DO 身份再试一次 —— 两条通道都试过才算失败，
            // 别因为一条通道被拒就整体放弃。
            if (admin.isDeviceOwner()) {
                val viaDo = admin.setSecureSetting(key, value)
                if (viaDo.isOk) return viaDo
                return OpResult.Failed(
                    "writeSecure",
                    "direct=${(direct as? OpResult.Failed)?.reason}; " +
                        "do=${(viaDo as? OpResult.Failed)?.reason}"
                )
            }
            return direct
        }

        if (admin.isDeviceOwner()) return admin.setSecureSetting(key, value)
        return OpResult.Failed(
            "writeSecure",
            "no silent-write channel (no WRITE_SECURE_SETTINGS, not DO); key=$key"
        )
    }

    /** 直写 `Settings.Secure`，把可能抛出的异常收敛为 [OpResult]，绝不向上冒泡。 */
    private fun putSecureDirect(key: String, value: String): OpResult = try {
        if (Settings.Secure.putString(context.contentResolver, key, value)) {
            OpResult.Ok
        } else {
            OpResult.Failed("putString", "Settings.Secure.putString returned false (key=$key)")
        }
    } catch (e: Exception) {
        OpResult.Failed("putString", "threw (key=$key): ${e.message}")
    }

    /** healIfNeeded 的四次写入必须逐次记录 OpResult：旧实现丢弃返回值，写失败只剩 "still missing"，排障方向被带偏。 */
    private fun logHealWrite(step: String, result: OpResult) {
        when (result) {
            is OpResult.Ok -> Logger.i(TAG) { "a11y heal $step write ok" }
            is OpResult.Failed -> Logger.w(TAG) { "a11y heal $step write FAILED: ${result.reason}" }
            is OpResult.Unsupported -> Logger.w(TAG) { "a11y heal $step write unsupported: ${result.reason}" }
        }
    }

    /**
     * 检测并在需要时执行自愈。非阻塞：内部切到 IO 线程执行禁用→延迟→启用序列。
     * 可安全重复调用（自带 once-per-process 守卫）。
     */
    suspend fun healIfNeeded() = withContext(Dispatchers.Default) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext
        if (attemptedThisProcess) return@withContext
        if (accessibilityScreenshot.isAvailable()) return@withContext
        attemptedThisProcess = true

        if (!canSilentHeal()) {
            Logger.w(TAG) {
                "a11y screenshot capability missing and no silent-write channel " +
                    "(DO or WRITE_SECURE_SETTINGS): ask guardian to re-toggle the service in " +
                    "Settings > Accessibility (or reinstall)"
            }
            return@withContext
        }

        opMutex.withLock {
            val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            val current = Settings.Secure.getString(context.contentResolver, key).orEmpty()
            val entries = splitEntries(current)
            val ours = entries.filter {
                ComponentName.unflattenFromString(it) == component || it.equals(flat, ignoreCase = true)
            }

            when {
                // 场景 1：服务根本没启用 —— 直接启用，启用即读取新元数据，一步到位
                ours.isEmpty() -> {
                    Logger.w(TAG) { "a11y guard service not enabled, enabling via silent-write (fresh metadata)" }
                    logHealWrite(
                        "enable",
                        writeSecure(key, withEntry(entries, flat).joinToString(":"))
                    )
                    delay(ENABLE_DELAY_MS)
                }
                // 场景 2：已启用但能力缺失（陈旧元数据）—— 禁用→启用，迫使系统重读
                else -> {
                    Logger.w(TAG) { "a11y service enabled but screenshot capability stale, silent re-toggle" }
                    val disabled = writeSecure(key, (entries - ours.toSet()).joinToString(":"))
                    logHealWrite("disable", disabled)
                    // 摘除失败时服务仍在名单里，不必再恢复（也可能已把名单写坏），直接跳过本轮。
                    if (disabled is OpResult.Ok) {
                        delay(DISABLE_DELAY_MS)
                        // 恢复写前重新读取：延迟期间若有别的健康操作（heal/rebind/disable）改动了名单，
                        // 直接回写旧快照会把对方改动整体覆盖，甚至把本服务重新加回启用名单（权限放大）。
                        // 改为「读当前值 → 仅确保本服务在列」再写回，绝不覆盖他人变更。
                        val nowEntries = splitEntries(Settings.Secure.getString(context.contentResolver, key))
                        val restored = withEntry(nowEntries, flat)
                        val restoreResult = writeSecure(key, restored.joinToString(":"))
                        logHealWrite("restore", restoreResult)
                        if (restoreResult !is OpResult.Ok) {
                            // 绝不能把服务留在"已摘除"状态：最后补救一次启用 + 总开关。
                            ensureEnabledLocked()
                        }
                        delay(ENABLE_DELAY_MS)
                    }
                }
            }
        }

        val healed = accessibilityScreenshot.isAvailable()
        if (healed) {
            Logger.i(TAG) { "a11y screenshot capability healed (metadata re-read ok)" }
        } else {
            Logger.w(TAG) {
                "a11y capability still missing after re-toggle; will retry on next process start"
            }
        }
    }

    /**
     * 确保无障碍服务处于启用态（DO 或已授权 WRITE_SECURE_SETTINGS 的设备可静默写系统设置）。
     *
     * 与 [healIfNeeded] 的区别：无论截图能力是否就绪，只要服务没在启用名单里就启用它。
     * 用于权限掉失自检：国产 ROM 在系统设置里切换"后台弹出界面 / 悬浮窗"等权限时会强制重启
     * 本应用，重启后无障碍服务绑定被解除（设置里"无障碍"项被系统自动关闭），可静默写的设备上
     * 这里重启用即可治愈（含 2026-11-17 起 EMUI「一键清理」停用无障碍后的自动恢复）；
     * 既无 DO 也无授权的设备则由 [com.padguard.child.guard.PermissionHealthChecker]
     * 诚实告警并引导家长去设置重新启用。
     */
    suspend fun ensureEnabled() = withContext(Dispatchers.Default) { ensureEnabledInternal() }

    /**
     * 内部实现：供 [rebindIfDetached] 复用（避免 suspend 嵌套调用带来的上下文切换）。
     */
    private suspend fun ensureEnabledInternal() {
        // API 29 起 DPM 才稳定接受 ENABLED_ACCESSIBILITY_SERVICES 这类 secure 设置；
        // 更早的版本写不进去（guarded 会返回 Failed），因此不做无谓尝试。
        // 注意这里**不能**用 R(30)：零弹窗截屏能力确实是 30 才有的，
        // 但"把被关掉的无障碍重新打开"这件事从 29 起就成立 —— 两者别混为一谈。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!canSilentHeal()) {
            Logger.w(TAG) { "cannot auto-enable a11y (no DO, no WRITE_SECURE_SETTINGS); ask guardian to enable in Settings" }
            return
        }
        opMutex.withLock { ensureEnabledLocked() }
    }

    /** [opMutex] 已持有时的实际写入逻辑（供 [rebindIfDetached] 复用，避免嵌套加锁死锁）。 */
    private fun ensureEnabledLocked() {
        val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        // 重新读取当前名单再追加，避免覆盖并发健康操作（如 rebind/disable）的改动。
        val current = Settings.Secure.getString(context.contentResolver, key).orEmpty()
        val entries = splitEntries(current)
        val next = withEntry(entries, flat)
        if (next.size != entries.size) {
            // 必须按写入结果分别记日志。旧实现在这里无条件打 "force-enabled"，
            // 2026-11-18 真机上出现了"日志说自愈成功、实际 accessibility_enabled 仍是 0"
            // 的假象，把排障方向直接带偏（真正原因是 DO 写无障碍键被系统拒绝）。
            when (val r = writeSecure(key, next.joinToString(":"))) {
                is OpResult.Ok ->
                    Logger.i(TAG) { "a11y service force-enabled via silent-write channel (permission self-heal)" }
                is OpResult.Failed ->
                    Logger.w(TAG) { "a11y service enable write FAILED: ${r.reason}" }
                is OpResult.Unsupported ->
                    Logger.w(TAG) { "a11y service enable unsupported: ${r.reason}" }
            }
        }
        // 总开关**无条件补写**，不能只在"名单发生变化"时才写。
        //
        // EMUI「一键清理」有两种落点：① 把我们移出启用名单；② 名单原样保留、
        // 只把 `ACCESSIBILITY_ENABLED`（无障碍总开关）置 0。旧实现只处理了 ① ——
        // 名单没变就整个跳过，于是 ② 场景下服务永远不启动，而
        // [GuardA11y.isEnabled] 读名单仍返回 true，检测侧看到的是"开关开着只是没连上"，
        // 走 [rebindIfDetached] 摘除再写回名单同样无效（名单本就没问题），
        // 形成"检测说在、实际不跑、怎么修都不动"的静默锁死。
        // 单键赋值是幂等的，代价只有一次 putString。
        ensureMasterSwitch()
    }

    /**
     * 开关开着但服务没连上时的**强制重绑**（仅 DO，API 29+）。
     *
     * ## 与 [ensureEnabled] 的分工
     * [ensureEnabled] 处理"名单里没有我们"；本方法处理"名单里有我们，但服务对象没连上"——
     * 典型成因是 ROM 杀掉进程后只恢复了设置项、没有重建绑定，或上次绑定以异常收场。
     * 处置是把这个组件从名单里摘掉、等系统解绑，再原样写回，迫使它重新走一遍
     * `onServiceConnected`。这是 DO 设备上唯一能"无界面"修复的方法。
     *
     * ## 为什么要限频
     * 摘除期间闸门是**真的失效**（几百毫秒到几秒）。若允许高频重绑，
     * 一次系统抖动就会变成"反复解绑"，反而制造出比原问题更长的空窗。
     * 因此 10 分钟至多一次；期间仍由系统自身的重绑机制兜底。
     *
     * @return true = 确实执行了重绑；false = 未执行（非 DO / 版本过低 / 仍在冷却期）
     */
    suspend fun rebindIfDetached(): Boolean = withContext(Dispatchers.Default) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext false
        if (!canSilentHeal()) {
            Logger.w(TAG) { "cannot rebind a11y (no DO, no WRITE_SECURE_SETTINGS); leave it to system rebind" }
            return@withContext false
        }
        opMutex.withLock {
            val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            val current = Settings.Secure.getString(context.contentResolver, key).orEmpty()
            val entries = splitEntries(current)
            val ours = entries.filter {
                it.equals(flat, ignoreCase = true) || ComponentName.unflattenFromString(it) == component
            }
            // 名单里压根没有：不是"未连接"而是"未启用"，交给 ensureEnabled（各自限频口径不同）
            if (ours.isEmpty()) {
                ensureEnabledLocked()
                return@withLock true
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastRebindAt < REBIND_COOLDOWN_MS) {
                Logger.i(TAG) { "a11y rebind skipped: cooling down (${(now - lastRebindAt) / 1000}s ago)" }
                return@withLock false
            }
            lastRebindAt = now
            Logger.w(TAG) { "a11y enabled but not attached -> forcing rebind (remove, wait, restore)" }
            // 摘除前先确认这条路走得通：若"摘除"写不进去，说明本机此刻没有可用的写入通道，
            // 此时更要**立刻中止**——绝不能出现"摘掉了却写不回"的状态，
            // 那会让无障碍从"没连上"变成"彻底没启用"，拦截闸门永久离线。
            val removed = writeSecure(key, (entries - ours.toSet()).joinToString(":"))
            if (removed !is OpResult.Ok) {
                Logger.w(TAG) {
                    "a11y rebind aborted: cannot write removal, keep current list intact (${removed})"
                }
                return@withLock false
            }
            delay(DISABLE_DELAY_MS)
            // 恢复写前重新读取：延迟期间若 disable/heal 改动了名单，回写旧快照会覆盖对方改动。
            val nowEntries = splitEntries(Settings.Secure.getString(context.contentResolver, key))
            val restored = withEntry(nowEntries, flat)
            val writeBack = writeSecure(key, restored.joinToString(":"))
            if (writeBack !is OpResult.Ok) {
                // 走到这里说明摘除成功、写回失败：服务当前**不在**启用名单里。
                // 如实告警（不静默），让上层在下一轮自检里重试 ensureEnabled 补回。
                Logger.w(TAG) {
                    "a11y rebind RESTORE FAILED, service is now detached and must be re-enabled: $writeBack"
                }
                // 尽最后一次努力直接补回，别把设备留在无闸门状态。
                runCatching { ensureEnabledLocked() }
                    .onFailure { Logger.w(TAG, it) { "a11y rebind last-resort re-enable failed" } }
                return@withLock false
            }
            // 摘除再写回只修"名单"，修不了"总开关为 0"（EMUI 清理的另一种落点）。
            // 不补这一句，重绑会在"名单正常、总开关 0"的设备上无限空转。
            ensureMasterSwitch()
            delay(ENABLE_DELAY_MS)
            true
        }
    }

    /**
     * 补写无障碍总开关 `ACCESSIBILITY_ENABLED`。
     *
     * 少数 ROM（EMUI/MIUI 的定制层）在只写 `ENABLED_ACCESSIBILITY_SERVICES` 而总开关为 0 时
     * 不会真正绑定服务，表现为"设置里明明勾着，服务就是不起来"。
     *
     * 该键未必在所有版本的 DPM 白名单内，写失败**不中断**主流程：服务列表已经写入，
     * 绝大多数平台仅凭它就能绑定。
     */
    private fun ensureMasterSwitch() {
        when (val r = writeSecure(Settings.Secure.ACCESSIBILITY_ENABLED, "1")) {
            is OpResult.Failed -> Logger.w(TAG) { "set ACCESSIBILITY_ENABLED failed (ignored): ${r.reason}" }
            else -> Unit
        }
    }

    /**
     * 静默禁用无障碍服务（仅设备解绑时调用）。
     *
     * 与 [ensureEnabled] 相反：把本服务从 `ENABLED_ACCESSIBILITY_SERVICES` 中移除，
     * 使"授权被钉死"的状态彻底解除。仅 Device Owner 可静默写系统设置；非 DO 设备无操作
     * （解绑后本就由引导流程重新授权，不影响正确性）。
     */
    suspend fun disable() = withContext(Dispatchers.Default) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext
        if (!canSilentHeal()) {
            Logger.w(TAG) { "cannot auto-disable a11y (no DO, no WRITE_SECURE_SETTINGS); skip" }
            return@withContext
        }
        opMutex.withLock {
            val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            val current = Settings.Secure.getString(context.contentResolver, key).orEmpty()
            val entries = splitEntries(current)
            val next = withoutEntry(entries, flat)
            writeSecure(key, next.joinToString(":"))
            Logger.i(TAG) { "a11y service disabled via silent-write channel (unbind)" }
        }
    }

    companion object {
        private const val TAG = "A11yCapabilityHealer"

        /**
         * 把 `ENABLED_ACCESSIBILITY_SERVICES` 原始串切成组件列表（纯函数，便于 JVM 测试）。
         * 空项/空白项一律丢弃；顺序原样保留（不能打乱系统自己的名单顺序）。
         */
        internal fun splitEntries(raw: String?): List<String> =
            raw.orEmpty().split(':').filter { it.isNotBlank() }

        /**
         * 确保本服务在名单里：已存在（忽略大小写）则**原样返回**，否则追加到末尾。
         * 绝不覆盖其他服务的条目 —— 静默自愈只动自己的那一项，这是权限边界红线。
         */
        internal fun withEntry(entries: List<String>, flat: String): List<String> =
            if (entries.any { it.equals(flat, ignoreCase = true) }) entries else entries + flat

        /** 从名单中摘掉本服务（解绑 / 强制重绑的"禁用"半程），其他服务原样保留。 */
        internal fun withoutEntry(entries: List<String>, flat: String): List<String> =
            entries.filterNot { it.equals(flat, ignoreCase = true) }

        /**
         * 设置观察者回调的只读判定：本服务是否**确实**被关掉（需要静默写回）。
         *
         * @param enabledRaw `ENABLED_ACCESSIBILITY_SERVICES` 原始串
         * @param master `ACCESSIBILITY_ENABLED` 总开关值（读取失败默认 1，即视为开）
         * @param flat 本服务组件扁平名
         * @return true = 名单里没有本服务，或总开关被置 0（EMUI 清理的两种落点）
         *
         * 纯函数，便于 JVM 单测钉死「健康态不写、两种清理落点都写」，
         * 是 ContentObserver 防自激循环的第一道闸。
         */
        internal fun needsReenable(enabledRaw: String?, master: Int, flat: String): Boolean {
            val ours = splitEntries(enabledRaw).any { it.equals(flat, ignoreCase = true) }
            return !ours || master == 0
        }

        /** 非 DO 设备的静默写入通道：装机时经 adb 一次性授予（见清单注释） */
        private const val PERMISSION_WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"

        /** 禁用后停留时长：给系统完成解绑的时间，避免"写回启用"被当作无效操作忽略 */
        private const val DISABLE_DELAY_MS = 1_500L

        /** 重新启用后等系统重绑、能力位生效的时长 */
        private const val ENABLE_DELAY_MS = 3_000L

        /**
         * 强制重绑的最小间隔。
         *
         * 重绑期间闸门是真失效：若因一次系统抖动就反复解绑，制造的空窗会比原问题更长。
         * 10 分钟一次，期间由系统自身重绑机制兜底。
         */
        private const val REBIND_COOLDOWN_MS = 10 * 60 * 1000L

        /** 设置观察回调的抖动合并窗口：ROM 一次清理常连改多把键，合并为一次判定。 */
        private const val WATCH_DEBOUNCE_MS = 250L

        /**
         * 自写回设置的异步回声抑制窗。
         * Settings.Secure.putString 触发的 ContentObserver 回调是异步的，写回后短暂窗口内
         * 的回调都是自己造成的，必须忽略，否则「读判定→写→回调→再判定」可能与 ROM 的
         * 二次清理交错成死循环。2 秒足以覆盖 SettingsProvider 的广播延迟。
         */
        private const val WRITE_ECHO_WINDOW_MS = 2_000L
    }
}
