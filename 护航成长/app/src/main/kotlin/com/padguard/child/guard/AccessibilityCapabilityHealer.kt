package com.padguard.child.guard

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.padguard.child.capture.AccessibilityScreenshot
import com.padguard.core.common.Logger
import com.padguard.core.engine.admin.DeviceAdminBridge
import com.padguard.core.engine.admin.OpResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * Device Owner 有权写 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`。
 * 把服务**先禁用再启用**一次，系统会以新版本元数据重新绑定 —— 能力位随重读生效。
 * 全程无界面、无弹窗，孩子无感知（约 3 秒内完成，期间拦截闸门短暂离线后自动恢复）。
 *
 * ## 触发纪律（防循环）
 * - 仅当：API 30+ 且服务能力缺失（[AccessibilityScreenshot.isAvailable] 为 false）；
 * - 每个进程生命周期至多尝试一次（内存标记）；成功后能力就位，天然不会再进本分支；
 * - 设备非 Device Owner 时无法静默写 secure 设置，只记日志给运维指引（手动开关或
 *   卸载重装——卸载会清掉服务启用态，重装后首次启用即读取新元数据，同样能治愈）。
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
     * 检测并在需要时执行自愈。非阻塞：内部切到 IO 线程执行禁用→延迟→启用序列。
     * 可安全重复调用（自带 once-per-process 守卫）。
     */
    suspend fun healIfNeeded() = withContext(Dispatchers.Default) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext
        if (attemptedThisProcess) return@withContext
        if (accessibilityScreenshot.isAvailable()) return@withContext
        attemptedThisProcess = true

        if (!admin.isDeviceOwner()) {
            Logger.w(TAG) {
                "a11y screenshot capability missing and no Device Owner: " +
                    "ask guardian to re-toggle the service in Settings > Accessibility (or reinstall)"
            }
            return@withContext
        }

        val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        val current = Settings.Secure.getString(context.contentResolver, key).orEmpty()
        val entries = current.split(':').filter { it.isNotBlank() }
        val ours = entries.filter {
            ComponentName.unflattenFromString(it) == component || it.equals(flat, ignoreCase = true)
        }

        when {
            // 场景 1：服务根本没启用 —— 直接启用，启用即读取新元数据，一步到位
            ours.isEmpty() -> {
                Logger.w(TAG) { "a11y guard service not enabled, enabling via Device Owner (fresh metadata)" }
                admin.setSecureSetting(key, (entries + flat).joinToString(":"))
                delay(ENABLE_DELAY_MS)
            }
            // 场景 2：已启用但能力缺失（陈旧元数据）—— 禁用→启用，迫使系统重读
            else -> {
                Logger.w(TAG) { "a11y service enabled but screenshot capability stale, silent re-toggle" }
                admin.setSecureSetting(key, (entries - ours.toSet()).joinToString(":"))
                delay(DISABLE_DELAY_MS)
                admin.setSecureSetting(key, current)
                delay(ENABLE_DELAY_MS)
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
     * 确保无障碍服务处于启用态（仅 Device Owner 可静默写系统设置）。
     *
     * 与 [healIfNeeded] 的区别：无论截图能力是否就绪，只要服务没在启用名单里就启用它。
     * 用于权限掉失自检：国产 ROM 在系统设置里切换"后台弹出界面 / 悬浮窗"等权限时会强制重启
     * 本应用，重启后无障碍服务绑定被解除（设置里"无障碍"项被系统自动关闭），DO 设备上这里
     * 静默重启用即可治愈，非 DO 设备则由 [com.padguard.child.guard.PermissionHealthChecker]
     * 诚实告警并引导家长去设置重新启用。
     */
    suspend fun ensureEnabled() = withContext(Dispatchers.Default) { ensureEnabledInternal() }

    /**
     * 内部实现：供 [rebindIfDetached] 复用（避免 suspend 嵌套调用带来的上下文切换）。
     */
    private fun ensureEnabledInternal() {
        // API 29 起 DPM 才稳定接受 ENABLED_ACCESSIBILITY_SERVICES 这类 secure 设置；
        // 更早的版本写不进去（guarded 会返回 Failed），因此不做无谓尝试。
        // 注意这里**不能**用 R(30)：零弹窗截屏能力确实是 30 才有的，
        // 但"把被关掉的无障碍重新打开"这件事从 29 起就成立 —— 两者别混为一谈。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!admin.isDeviceOwner()) {
            Logger.w(TAG) { "cannot auto-enable a11y without Device Owner; ask guardian to enable in Settings" }
            return
        }
        val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        val current = Settings.Secure.getString(context.contentResolver, key).orEmpty()
        val entries = current.split(':').filter { it.isNotBlank() }
        val ours = entries.any { it.equals(flat, ignoreCase = true) }
        if (!ours) {
            admin.setSecureSetting(key, (entries + flat).joinToString(":"))
            ensureMasterSwitch()
            Logger.i(TAG) { "a11y service force-enabled via Device Owner (permission self-heal)" }
        }
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
        if (!admin.isDeviceOwner()) {
            Logger.w(TAG) { "cannot rebind a11y without Device Owner; leave it to system rebind" }
            return@withContext false
        }
        val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        val current = Settings.Secure.getString(context.contentResolver, key).orEmpty()
        val entries = current.split(':').filter { it.isNotBlank() }
        val ours = entries.filter {
            it.equals(flat, ignoreCase = true) || ComponentName.unflattenFromString(it) == component
        }
        // 名单里压根没有：不是"未连接"而是"未启用"，交给 ensureEnabled（各自限频口径不同）
        if (ours.isEmpty()) {
            ensureEnabledInternal()
            return@withContext true
        }
        val now = System.currentTimeMillis()
        if (now - lastRebindAt < REBIND_COOLDOWN_MS) {
            Logger.i(TAG) { "a11y rebind skipped: cooling down (${(now - lastRebindAt) / 1000}s ago)" }
            return@withContext false
        }
        lastRebindAt = now
        Logger.w(TAG) { "a11y enabled but not attached -> forcing rebind (remove, wait, restore)" }
        admin.setSecureSetting(key, (entries - ours.toSet()).joinToString(":"))
        delay(DISABLE_DELAY_MS)
        admin.setSecureSetting(key, entries.joinToString(":"))
        delay(ENABLE_DELAY_MS)
        true
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
        when (val r = admin.setSecureSetting(Settings.Secure.ACCESSIBILITY_ENABLED, "1")) {
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
        if (!admin.isDeviceOwner()) {
            Logger.w(TAG) { "cannot auto-disable a11y without Device Owner; skip" }
            return@withContext
        }
        val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        val current = Settings.Secure.getString(context.contentResolver, key).orEmpty()
        val entries = current.split(':').filter { it.isNotBlank() }
        val next = entries.filter { !it.equals(flat, ignoreCase = true) }
        admin.setSecureSetting(key, next.joinToString(":"))
        Logger.i(TAG) { "a11y service disabled via Device Owner (unbind)" }
    }

    companion object {
        private const val TAG = "A11yCapabilityHealer"

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
    }
}
