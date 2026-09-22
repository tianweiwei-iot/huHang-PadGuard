package com.padguard.child.capture

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.padguard.core.common.Logger
import dagger.hilt.android.AndroidEntryPoint

/**
 * 屏幕采集授权页（无界面），同时是**采集请求的必经入口**。
 *
 * ## 为什么必须先经过本页
 * Android 14+ 上，`mediaProjection` 类型的前台服务要求调用方持有 `android:project_media`
 * appop；该 appop 只有在用户通过系统"是否允许录制或投射屏幕"弹窗确认后才会授予。
 * 因此从后台直接 `startForegroundService(ScreenCaptureService)` 必然抛
 * `SecurityException: Starting FGS with type mediaProjection ... requires permissions`。
 * 历史上正是这个顺序错误导致远程截屏/录屏**永远失败**（服务端却显示已受理）。
 *
 * 正确顺序：后台收到采集指令 → 拉起本页（前台 Activity）→ 系统授权弹窗 →
 * 拿到令牌 → 由本页以 `startForegroundService` 起采集服务 → 服务内复用令牌采集。
 * 已持有令牌时本页直接放行，不会重复弹窗。
 *
 * 画面是透明的，用户只会看到系统确认框，不会看到我们的界面。
 */
@AndroidEntryPoint
class CapturePermissionActivity : ComponentActivity() {

    /** 待执行的采集请求（screenshot / screen_record / audio_record …） */
    private var captureIntent: Intent? = null

    private val requestCapture = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (result.resultCode == Activity.RESULT_OK && data != null && manager != null) {
            // 只存令牌不取投影：此刻还没有 mediaProjection 前台服务，
            // 在这里 getMediaProjection 会被系统直接拒绝（Android 14+）
            ScreenCaptureSession.storeToken(manager, result.resultCode, data)
            Logger.i(TAG) { "media projection granted, token stored" }
            dispatch()
        } else {
            Logger.w(TAG) { "media projection denied, dropping pending capture" }
            ScreenCaptureService.pendingIntent = null
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        captureIntent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_CAPTURE_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_CAPTURE_INTENT)
        }

        // 无论结果如何都记一次"已申请"，避免开机反复弹授权框骚扰孩子
        CaptureConsent.markRequested(this)

        if (ScreenCaptureSession.hasToken()) {
            dispatch()
            finish()
            return
        }

        // 音频采集不需要 MediaProjection 授权，直接放行，避免为录音也弹一次屏幕录制框
        if (isAudioOnly(captureIntent)) {
            dispatch()
            finish()
            return
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            Logger.e(TAG) { "MediaProjectionManager unavailable" }
            finish()
            return
        }
        runCatching { requestCapture.launch(manager.createScreenCaptureIntent()) }
            .onFailure {
                Logger.e(TAG, it) { "failed to launch capture consent" }
                finish()
            }
    }

    private fun isAudioOnly(target: Intent?): Boolean =
        target?.getStringExtra(ScreenCaptureService.EXTRA_ACTION) ==
            ScreenCaptureService.ACTION_AUDIO_RECORD ||
            target?.getStringExtra(ScreenCaptureService.EXTRA_ACTION) ==
            ScreenCaptureService.ACTION_STOP_AUDIO_RECORD

    private fun dispatch() {
        val target = captureIntent ?: return
        captureIntent = null
        ScreenCaptureService.startAuthorized(this, target)
    }

    companion object {
        private const val TAG = "CapturePermissionActivity"
        private const val EXTRA_CAPTURE_INTENT = "capture_intent"

        fun intent(context: Context): Intent =
            Intent(context, CapturePermissionActivity::class.java)

        /** 携带一条采集请求拉起本页；授权到位后由本页真正启动采集服务 */
        fun startWith(context: Context, captureIntent: Intent) {
            val intent = Intent(context, CapturePermissionActivity::class.java).apply {
                putExtra(EXTRA_CAPTURE_INTENT, captureIntent)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
                .onFailure { Logger.e(TAG, it) { "failed to open capture permission" } }
        }

        /**
         * 构建「点击即跳转到屏幕采集授权页」的 PendingIntent。
         *
         * 为什么需要它：家长端下发截屏/录屏指令时，被控端通常在后台（守护服务）收到，
         * 而 Android 10+ 禁止后台直接弹 Activity（[CapturePermissionActivity.startWith] 可能被系统拦掉），
         * 导致孩子永远看不到授权弹窗、家长端一直"等待授权"。
         * 由守护服务发一条高优先级通知，点击通知的 PendingIntent 启动授权页是**用户主动触发**，
         * 系统必然放行 —— 这是 P1「首次需授权后一直无画面」的可靠解法。
         */
        fun capturePermissionPendingIntent(context: Context, captureIntent: Intent): PendingIntent {
            val intent = Intent(context, CapturePermissionActivity::class.java).apply {
                putExtra(EXTRA_CAPTURE_INTENT, captureIntent)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            return PendingIntent.getActivity(context, 0, intent, flags)
        }
    }
}
