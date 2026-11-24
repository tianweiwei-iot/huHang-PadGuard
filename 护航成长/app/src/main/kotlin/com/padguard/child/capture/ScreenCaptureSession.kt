package com.padguard.child.capture

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import com.padguard.core.common.Logger

/**
 * 屏幕采集凭据（MediaProjection）的进程内缓存。
 *
 * ## 为什么"存令牌"和"取投影"必须分成两步（Android 14+ 硬约束）
 * API 34 起，连 `MediaProjectionManager.getMediaProjection()` 都要求
 * **调用方已经处于 mediaProjection 类型的前台服务中**，否则直接抛
 * `SecurityException: Media projections require a foreground service of type
 * FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`（实测进程直接崩溃）。
 *
 * 而带该类型的**前台服务又必须先拿到用户授权才能启动**（否则 startForeground 抛
 * `Starting FGS with type mediaProjection requires permissions`）。
 * 两者互为前置，唯一可行的顺序是：
 *
 * ```
 * 授权页弹系统确认框 → 用户同意 → 只把 resultCode+data 存下来（不取投影）
 *   → startForegroundService 起采集服务 → 服务 startForeground 成功
 *   → 服务内再调 obtainProjection() 真正创建 MediaProjection
 * ```
 *
 * 历史实现把两步并在授权页里做，在 Android 14+ 上 100% 崩溃，远程截屏/录屏全部失效。
 *
 * ## 生命周期边界
 * 令牌不能跨进程重建存活：进程重启后仍须重新授权（隐私设计，Device Owner 也无静默授权接口）。
 */
object ScreenCaptureSession {

    private const val TAG = "ScreenCaptureSession"

    @Volatile private var manager: MediaProjectionManager? = null

    /** 用户同意后拿到的原始授权结果；换取投影前一直保留 */
    @Volatile private var resultIntent: Intent? = null

    @Volatile private var resultCode: Int = Activity.RESULT_CANCELED

    /**
     * 本次 consent Intent 是否已被 `getMediaProjection()` 消费。
     *
     * Android 14+（本工程 targetSdk 35，实测机 TB330FU / Android 14）**一次用户确认
     * 只能换取一次 MediaProjection**：投影 stop 之后再用同一个 Intent 调用
     * `getMediaProjection()` 会抛 SecurityException。旧实现 `stop()` 投影却保留 Intent，
     * 于是 `hasToken()` 假阳性 → 静默起服务 → 开流中途失败 → 再弹授权框。
     * 现在用这个标志如实表达「凭据已经用过」，让 [hasToken] 不再骗人。
     */
    @Volatile private var consentConsumed = false

    @Volatile var mediaProjection: MediaProjection? = null
        private set

    /** 投影失效通知（用户撤销授权 / 系统回收 / 主动 stop） */
    @Volatile private var onLost: (() -> Unit)? = null

    fun setOnProjectionLost(listener: (() -> Unit)?) {
        onLost = listener
    }

    /**
     * 是否已持有**还能换取投影**的凭据：活动投影还在，或用户刚给过一次尚未消费的确认。
     * 已消费的 consent 不能再算「有令牌」（Android 14+ 一次性语义）。
     */
    fun hasToken(): Boolean = projectionHasToken(mediaProjection != null, resultIntent != null, consentConsumed)

    /** 是否需要重新向孩子申请一次系统确认（投影已失且 consent 已消费）。 */
    fun needsFreshConsent(): Boolean =
        projectionNeedsFreshConsent(mediaProjection != null, resultIntent != null, consentConsumed)

    /**
     * 第一步：只保存授权结果，**不**创建投影。
     * 此刻应用还没有前台服务，创建投影必然被系统拒绝。
     */
    fun storeToken(mgr: MediaProjectionManager, code: Int, data: Intent) {
        if (code != Activity.RESULT_OK || data.extras == null) {
            revoke()
            return
        }
        manager = mgr
        resultCode = code
        resultIntent = data
        // 用户刚在系统弹窗里确认过，这是一枚全新的、尚未消费的 consent
        consentConsumed = false
    }

    /**
     * 第二步：在 mediaProjection 类型前台服务内调用，真正创建投影。
     * 幂等：已有投影直接返回；没有令牌返回 null。
     */
    fun obtainProjection(): MediaProjection? {
        mediaProjection?.let { return it }
        // consent 已消费（投影被 stop / 系统回收）时绝不复用：Android 14+ 必然抛
        // SecurityException。直接返回 null，调用方走「重新申请一次授权」的明确路径，
        // 而不是「先静默起服务、开流中途才弹框」。
        if (consentConsumed) return null
        val mgr = manager ?: return null
        val data = resultIntent ?: return null
        return runCatching {
            val projection = mgr.getMediaProjection(resultCode, data)
            // Android 14+ 要求 createVirtualDisplay 前必须注册回调，
            // 否则抛 IllegalStateException，远程截屏在 API 34+ 设备上 100% 失败
            projection.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        Logger.w(TAG) { "projection onStop fired, live projection cleared (consent retained)" }
                        if (mediaProjection === projection) {
                            mediaProjection = null
                            // 关键：保留原始授权凭据 resultIntent。系统强杀投影后 IBinder 可能失效，
                            // 但下次复用由 obtainProjection 的 try/catch 安全判定并清理；此处若误清，
                            // 会导致"返回主页再进入看屏"被迫重新弹系统授权框（权限丢失根因之一）。
                            onLost?.invoke()
                        }
                    }
                },
                Handler(Looper.getMainLooper())
            )
            mediaProjection = projection
            projection
        }.onSuccess {
            // 仅在真正拿到投影后才把一次性 consent 标记为已消费：否则 getMediaProjection
            // 因瞬时竞态（如 mediaProjection 类型前台服务的 appop 尚未被系统激活）抛 SecurityException 时，
            // 会白白烧掉 Android 14+ 的一次性授权，导致下次本可复用同一 resultIntent 却被迫重新向孩子弹框。
            consentConsumed = true
        }.onFailure {
            // 失败时**不要**把持久化的授权事实一起抹掉：resultIntent 留着供诊断与 revoke 清理，
            // consentConsumed 保持 false，下次可用同一凭据立即重试，无需重新申请授权。
            Logger.e(TAG, it) { "obtain projection failed (consent retained for retry)" }
        }.getOrNull()
    }

    /** 取当前投影；未授权或尚未在服务内换取时为 null */
    fun projection(): MediaProjection? = mediaProjection

    /**
     * 暂停推流（退出看屏/录屏/息屏时调用）：**只停推流，不销毁投影**。
     *
     * ## 为什么绝不能在这里 `mediaProjection.stop()`（2026-11-07 授权反复丢失根因）
     * Android 14+ 的用户确认只能换取一次 MediaProjection。旧实现每轮看屏结束都 `stop()`
     * 投影，却把同一个 `resultIntent` 留着当"令牌还在"；下次开流复用一次性 consent
     * 必然抛 SecurityException，`obtainProjection` 的 onFailure 又把 Intent 清空 →
     * 孩子端再次弹出「是否允许录制屏幕」。服务端 8s 重发开流指令把这个闭环反复重放，
     * 表现就是"刚刚看屏还要孩子再确认一次授权"。
     *
     * 现在投影、常驻 VirtualDisplay 与 ImageReader 在整个进程生命周期内保活，
     * 多轮看屏复用**同一个**投影实例，不需要再取一次 consent。
     * 代价：采集前台服务（状态栏投影芯片）在看屏结束后仍可见，直到解绑或系统回收；
     * 对儿童设备来说这是必要且更透明的取舍，见 [ScreenCaptureService.stopIfIdle]。
     */
    fun suspendProjection() {
        // 故意空实现：调用方以为在"挂起"，实际语义是"保留会话，仅停推流"。
        Logger.i(TAG) { "live stream suspended, projection session kept alive (no re-consent needed)" }
    }

    /**
     * 撤销授权（仅设备解绑时调用）：彻底清空投影实例与原始凭据，并复位一次性标志。
     */
    fun revoke() {
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        resultIntent = null
        consentConsumed = false
    }
}

/**
 * 投影凭据状态判定（纯 Kotlin，无 Android 依赖，供 JVM 单测）。
 *
 * 修复前的 `hasToken()` = `mediaProjection != null || resultIntent != null`：
 * 投影被 `stop()` 后 `resultIntent` 仍在，于是返回 true → 静默起服务 →
 * 复用一次性 consent 失败 → 中途弹授权框。这两条纯函数把「一次性」语义显式钉住。
 */
internal fun projectionHasToken(hasProjection: Boolean, hasIntent: Boolean, consumed: Boolean): Boolean =
    hasProjection || (hasIntent && !consumed)

internal fun projectionNeedsFreshConsent(
    hasProjection: Boolean,
    hasIntent: Boolean,
    consumed: Boolean
): Boolean = !hasProjection && (!hasIntent || consumed)
