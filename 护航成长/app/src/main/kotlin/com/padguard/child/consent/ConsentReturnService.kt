package com.padguard.child.consent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.padguard.child.MainActivity
import com.padguard.child.permission.SpecialPermission
import com.padguard.core.common.Logger
import com.padguard.core.engine.admin.DeviceAdminBridge
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 授权自动回前台服务 ——「免手动返回」的真正执行体。
 *
 * ## 为什么必须是前台服务（而非直接 startActivity / 全屏通知）
 * 家长在系统设置页拨开关时，本 App 处于后台，此时有两道 Android 限制：
 * 1) Android 10+ 禁止后台启动 Activity，直接 `startActivity` 会被系统静默丢弃；
 * 2) full-screen intent 通知在平板处于解锁使用态时会降级为需手动点按的悬浮通知，
 *    等于把「点继续」换成「点通知」——家长仍然要手动操作，这正是上一版被吐槽的根因。
 *
 * 前台服务是从前台态（授权页仍可见时由 [ConsentReturnPresenter] 启动，落在豁免窗口内）
 * 拉起、随后常驻轮询的实体；它检测目标权限被授予后调用 `startActivity` 把授权页重新顶回前台，
 * 授权页 `onResume` → `advance` 自动接下一项。整条链「拨一个开关就自动前进一个」，
 * 家长全程**无需按返回、无需点通知**。相较「发通知」式伪免返回，这是从源头消除手动返回。
 *
 * ## 不会无限空转
 * 轮询上限 [MAX_ROUNDS] 后自动停手并 `stopSelf`：家长中途离开、或某项 ROM 不提供入口时，
 * 不占用 CPU、不耗电；超时后回退到「手动按返回」的旧行为，流程不悬空。
 */
@AndroidEntryPoint
class ConsentReturnService : Service() {

    @Inject
    lateinit var admin: DeviceAdminBridge

    @Inject
    lateinit var notifier: ConsentReturnNotifier

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 必须在 onStartCommand 之前调用，否则系统直接 ANR 杀服务
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val keys = intent?.getStringArrayListExtra(EXTRA_KEYS).orEmpty()
        Logger.i(TAG) { "consent return service started, watching=$keys" }
        job?.cancel()
        job = scope.launch {
            var round = 0
            while (isActive && round < MAX_ROUNDS) {
                if (keys.any { isGranted(it) }) {
                    Logger.i(TAG) { "target granted, bringing consent screen to front" }
                    bringToFront()
                    break
                }
                round++
                delay(POLL_MS)
            }
            if (round >= MAX_ROUNDS) {
                Logger.w(TAG) { "consent return watcher timed out after $MAX_ROUNDS rounds (manual back may be needed)" }
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        // 撤掉本服务的常驻通知，避免授权完成后通知栏残留一条过期提示
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            .onFailure { Logger.w(TAG) { "stop foreground failed: ${it.message}" } }
        super.onDestroy()
    }

    /** 目标是否已达成：设备管理员走 [DeviceAdminBridge.isAdminActive]，其余走 [SpecialPermission.isGranted]。 */
    private fun isGranted(key: String): Boolean = when (key) {
        KEY_DEVICE_ADMIN -> admin.isAdminActive()
        else -> SpecialPermission.fromKey(key)?.isGranted(this) ?: false
    }

    /**
     * 把授权页宿主（[MainActivity]）重新顶回前台。
     *
     * 前台服务自前台态拉起后 `startActivity` 合法，不受 Android 10+ 后台启动限制；
     * 一旦启动失败（极端 ROM 仍拦截），退化为全屏通知兜底，家长点一下也能回来——不悬空。
     */
    private fun bringToFront() {
        val ok = runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    // 标记「续跑授权流程」：若授权页实例被系统回收后重建，入口据此自动续跑，
                    // 不会因 ViewModel 复位而卡在初始态（表现为"点了完成没反应"）。
                    putExtra(EXTRA_RESUME_CONSENT, true)
                }
            )
        }.isSuccess
        if (!ok) {
            Logger.w(TAG) { "background bring-to-front blocked, falling back to notification" }
            notifier.postReturnToConsent()
        }
    }

    private fun buildNotification(): Notification {
        val mgr = runCatching { getSystemService(NotificationManager::class.java) }.getOrNull()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "授权返回引导", NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("正在等待授权完成")
            .setContentText("请在系统页面开启对应权限，完成后将自动返回")
            .setSmallIcon(applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        /** 传输给服务的盯守目标 key 列表 */
        const val EXTRA_KEYS = "consent_return_keys"

        /** 设备管理员激活的盯守 key，与 [ConsentReturnPresenter]、[OneTimeConsentViewModel] 约定一致 */
        const val KEY_DEVICE_ADMIN = "deviceAdmin"

        /** 前台服务把授权页顶回前台时携带的标记：入口据此续跑授权链（防止实例重建后卡初始态） */
        const val EXTRA_RESUME_CONSENT = "consent_resume"

        private const val CHANNEL_ID = "padguard_consent_return_svc"
        private const val NOTIF_ID = 9104
        private const val POLL_MS = 400L

        /** 约 60 秒上限：足够等家长拨完一个开关，又不至于长期空转 */
        private const val MAX_ROUNDS = 150
        private const val TAG = "ConsentReturnSvc"
    }
}
