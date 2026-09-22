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

    @Volatile var mediaProjection: MediaProjection? = null
        private set

    /** 投影失效通知（用户撤销授权 / 系统回收 / 主动 stop） */
    @Volatile private var onLost: (() -> Unit)? = null

    fun setOnProjectionLost(listener: (() -> Unit)?) {
        onLost = listener
    }

    /** 是否已持有可用凭据（已授权未取投影 / 投影仍在，都算可用） */
    fun hasToken(): Boolean = mediaProjection != null || resultIntent != null

    /**
     * 第一步：只保存授权结果，**不**创建投影。
     * 此刻应用还没有前台服务，创建投影必然被系统拒绝。
     */
    fun storeToken(mgr: MediaProjectionManager, code: Int, data: Intent) {
        if (code != Activity.RESULT_OK || data.extras == null) {
            release()
            return
        }
        manager = mgr
        resultCode = code
        resultIntent = data
    }

    /**
     * 第二步：在 mediaProjection 类型前台服务内调用，真正创建投影。
     * 幂等：已有投影直接返回；没有令牌返回 null。
     */
    fun obtainProjection(): MediaProjection? {
        mediaProjection?.let { return it }
        val mgr = manager ?: return null
        val data = resultIntent ?: return null
        return runCatching {
            val projection = mgr.getMediaProjection(resultCode, data)
            // Android 14+ 要求 createVirtualDisplay 前必须注册回调，
            // 否则抛 IllegalStateException，远程截屏在 API 34+ 设备上 100% 失败
            projection.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        Logger.w(TAG) { "projection onStop fired, clearing cached projection" }
                        if (mediaProjection === projection) {
                            mediaProjection = null
                            resultIntent = null
                            onLost?.invoke()
                        }
                    }
                },
                Handler(Looper.getMainLooper())
            )
            mediaProjection = projection
            projection
        }.onFailure {
            Logger.e(TAG, it) { "obtain projection failed" }
            resultIntent = null
        }.getOrNull()
    }

    /** 取当前投影；未授权或尚未在服务内换取时为 null */
    fun projection(): MediaProjection? = mediaProjection

    /** 释放投影（采集会话结束时调用，避免长期占用编解码资源） */
    fun release() {
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        resultIntent = null
    }
}
