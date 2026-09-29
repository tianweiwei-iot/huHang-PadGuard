package com.padguard.child.capture

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.padguard.child.AppForeground
import com.padguard.core.common.Logger

/**
 * 屏幕采集请求的统一派发口。
 *
 * ## 为什么必须收敛到一个入口
 * Android 10+ 禁止后台直接启动 Activity。`CapturePermissionActivity.startWith` 从
 * 守护服务（后台）里调用会被系统静默拦掉：孩子永远看不到"是否允许录制屏幕"的弹窗，
 * 家长端就一直停在"等待授权"，截屏 / 录屏 / 实时看屏全线失败。
 *
 * 历史实现里只有截屏走了"通知引导"路径，录屏与实时看屏仍然直接 startActivity ——
 * 这正是「截屏偶尔成功、录屏永远启动失败」的直接原因。
 *
 * ## 派发规则
 * - 已持有凭据（装机时家长已确认过一次）→ **直接进入采集前台服务，全程无弹窗、无感知**；
 * - 没有凭据（首次装机 / 设备重启后进程内令牌失效）→ 发一条高优先级通知，
 *   点击通知即跳转授权页。这是"用户主动触发"，系统必然放行。
 *
 * ## 关于"必须点一次授权"
 * MediaProjection 的用户确认是 Android 的隐私红线，Device Owner / 系统签名都绕不过，
 * 任何声称"完全静默"的实现都不可信。正确做法是**把这一次确认放在装机环节由家长完成**，
 * 之后长期复用（进程内存活期间永不重复弹窗）—— 这也是主流家长管控产品的通行做法。
 */
object CaptureConsentPrompter {

    private const val TAG = "CaptureConsentPrompter"
    private const val CHANNEL_ID = "padguard_consent"
    private const val NOTIFICATION_ID = 1003

    /** 启动授权页后多久仍未落地就判定被系统丢弃，补发通知 */
    private const val LAUNCH_CONFIRM_DELAY_MS = 1_500L

    /**
     * 派发一条采集请求。
     *
     * @return true = 已授权，请求已直接下发（静默）；false = 尚无授权，已发通知引导
     */
    fun dispatch(context: Context, captureIntent: Intent): Boolean {
        if (ScreenCaptureSession.hasToken()) {
            ScreenCaptureService.startAuthorized(context, captureIntent)
            return true
        }
        // 平板此刻正被使用（本应用在前台）→ 直接拉起授权页，孩子只需点一次"立即开始"；
        // 只有真正没人用平板时才退化成通知引导。二者缺一不可：
        // 只走通知，孩子在用平板也会漏看；只直接弹框，后台启动会被系统静默拦掉，
        // 家长端就永远停在"连接中"。
        if (AppForeground.isForeground) {
            val attemptAt = android.os.SystemClock.elapsedRealtime()
            if (launchConsentActivity(context, captureIntent)) {
                // 直接启动并非一定成功：Android 10+ 对后台启动 Activity 是**静默丢弃**，
                // 既不抛异常也没有任何回调，startActivity 照常返回。
                // 而"本应用是否在前台"的判定本身也可能失真（多进程/计数漂移）。
                // 因此延时确认授权页是否真的 onCreate 了，没有就补发通知兜底 ——
                // 否则家长端会永远停在"连接中"，平板上却什么提示都没有。
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (CapturePermissionActivity.lastCreateAtMs < attemptAt) {
                        Logger.w(TAG) { "consent activity silently dropped, fallback to notification" }
                        prompt(context, captureIntent)
                    }
                }, LAUNCH_CONFIRM_DELAY_MS)
                return false
            }
        }
        prompt(context, captureIntent)
        return false
    }

    /**
     * 直接拉起授权页。
     *
     * @return 是否真的把 Activity 拉起来了。系统对后台启动是**静默丢弃**而不是抛异常，
     * 因此这里用前台标记先判断一次，再兜一层 runCatching 防止个别 ROM 抛异常。
     */
    private fun launchConsentActivity(context: Context, captureIntent: Intent): Boolean =
        runCatching {
            val intent = Intent(context, CapturePermissionActivity::class.java).apply {
                putExtra(CapturePermissionActivity.EXTRA_CAPTURE_INTENT, captureIntent)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Logger.i(TAG) { "consent activity launched (app in foreground)" }
            true
        }.onFailure { Logger.w(TAG) { "launch consent activity failed: ${it.message}" } }
            .getOrDefault(false)

    /**
     * 需要采集但还没有凭据：发一条高优先级通知引导授权。
     *
     * 用独立的高优先级渠道而非守护服务的常驻渠道：常驻渠道是 IMPORTANCE_LOW，
     * 通知不会弹出、孩子可能永远看不到，等于没发。
     */
    fun prompt(context: Context, captureIntent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager == null) {
            Logger.e(TAG) { "NotificationManager unavailable, cannot prompt capture consent" }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID, "屏幕采集授权", NotificationManager.IMPORTANCE_HIGH
            ).apply { setShowBadge(false) }
            manager.createNotificationChannel(channel)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(
            context,
            0,
            Intent(context, CapturePermissionActivity::class.java).apply {
                putExtra(CapturePermissionActivity.EXTRA_CAPTURE_INTENT, captureIntent)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            flags
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("护航守护")
            .setContentText("家长请求查看屏幕：点击此处开启屏幕采集")
            .setSmallIcon(context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_menu_gallery)
            .setContentIntent(pi)
            .setOngoing(false)
            .setAutoCancel(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Logger.e(TAG, it) { "failed to post capture consent notification" } }
    }

    /** 授权完成后清掉引导通知，避免孩子看到一条已经过期的提醒 */
    fun clear(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }
}
