package com.padguard.child.capture

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.padguard.child.AppForeground
import com.padguard.child.ConsoleState
import com.padguard.child.notify.NotificationDelivery
import com.padguard.core.common.Logger
import java.util.concurrent.atomic.AtomicLong

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
 *
 * 两条已落地的降扰设计：
 * 1. **未绑定不打扰**：装机阶段（协议 → 登录 → 绑定）没有任何家长会下发采集指令，
 *    暖令牌整体跳过，不再每次打开 App 都弹系统确认框；
 * 2. **截屏走无障碍通道**（见 [AccessibilityScreenshot]）：`CMD_SCREENSHOT` 优先用
 *    无障碍服务的 takeScreenshot 完成，该通道的授权（启用无障碍服务）本身持久有效，
 *    截屏从此与 MediaProjection 无关，不会再弹"是否允许录制屏幕"。
 *    实时看屏 / 录屏仍依赖投影令牌，保持"装机一次 + 每次重启后首次解锁"的最少确认。
 */
object CaptureConsentPrompter {

    private const val TAG = "CaptureConsentPrompter"
    private const val CHANNEL_ID = "padguard_consent"
    private const val NOTIFICATION_ID = 1003

    /** 启动授权页后多久仍未落地就判定被系统丢弃，补发通知 */
    private const val LAUNCH_CONFIRM_DELAY_MS = 1_500L

    /**
     * 授权引导最小间隔（单调钟 + 高水位）。服务端 `MonitorService.reconcileLiveSessions`
     * 每 8s 用**新 msgId** 重发一次 LIVE_VIEW_START，端上幂等去重挡不住；若采集通道未就绪，
     * 旧实现每一条都会弹一次授权页/通知，孩子端表现为「反复要我确认」。
     */
    private const val PROMPT_COOLDOWN_MS = 60_000L

    private val lastPromptAtMs = AtomicLong(Long.MIN_VALUE)

    /**
     * 派发一条采集请求。
     *
     * @param silentIfBackground 后台态下是否静默（不弹通知）。
     * @return true = 已授权，请求已直接下发（静默）；false = 尚无授权，已发通知引导（或静默跳过）
     */
    fun dispatch(context: Context, captureIntent: Intent, silentIfBackground: Boolean = false): Boolean {
        if (ScreenCaptureSession.hasToken()) {
            // 已授权：静默复用，不受节流限制（这是家长每次看屏的正常路径）。
            // 顺手撤掉上一轮指令 / 上一个进程留下的引导通知：普通通知不会因为"令牌
            // 已经拿到了"自动消失，不撤就会一直挂着"请确认屏幕采集授权"，看起来像没授权成功。
            clear(context)
            ScreenCaptureService.startAuthorized(context, captureIntent)
            return true
        }
        // 没有可用凭据：是否允许向当前设备持有者弹确认框，取决于此刻是否处于「家长控制台 / 授权引导」
        // 在场（[com.padguard.child.ConsoleState.isManagementVisible]）。
        //
        // 孩子正常使用设备（隐藏态）时**绝不打扰孩子**——看屏授权必须在装机或家长经「补授权」
        // 入口主动完成时取得，使用期不再依赖孩子确认。此时静默返回 false（采集不执行），
        // 家长侧会看到看屏/录屏指令失败，并由家长去补授权入口重新确认屏幕采集。
        // （见需求：使用过程中就不要指望孩子确认授权了。）
        if (!ConsoleState.isManagementVisible) {
            Logger.i(TAG) {
                "capture token missing during normal child use; skip child prompt. " +
                    "parent must re-grant via 补授权 entry"
            }
            clear(context)
            return false
        }
        // 通知被系统关闭、此刻又不在前台：既发不出引导通知、也拉不起授权页。
        // 此时占用一次 60 秒节流额度毫无意义，还会把权限恢复后的第一次补发一并吞掉（R34）。
        if (!AppForeground.isForeground && !NotificationDelivery.canDeliver(context, CHANNEL_ID)) {
            Logger.w(TAG) {
                "capture consent prompt impossible: app backgrounded and notifications disabled"
            }
            return false
        }
        // 家长在场（揭示会话 / 一次性授权流程）：沿用原逻辑，由家长在系统确认框点「立即开始」。
        // 同一轮自愈重发只打扰一次。
        if (!claimPromptSlot()) {
            Logger.i(TAG) { "consent prompt throttled: waiting for a11y rebind / child confirmation" }
            return false
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
                        Logger.w(TAG) { "consent activity silently dropped, fallback" }
                        if (!silentIfBackground) prompt(context, captureIntent)
                    }
                }, LAUNCH_CONFIRM_DELAY_MS)
                return false
            }
        }
        if (silentIfBackground) return false
        prompt(context, captureIntent)
        return false
    }

    /**
     * 授权节奏（2026-10-17 定稿）：**装机时确认一次，日常永不主动弹**。
     *
     * ## 为什么"暖令牌"系列主动弹框全部移除（warmUp / onDeviceInteractive 已删）
     * 曾尝试把系统确认框收拢到"解锁 / 开机 / 回到前台"等时刻（暖令牌），
     * 实测全部违背产品预期：挂解锁 → "每次打开平板都弹"；挂回前台 →
     * "每次打开被管控应用、拦截页一出来就弹"。只要存在任何主动触发点，
     * 孩子就会周期性看到投屏授权框——与监护场景的隐蔽性要求根本冲突。
     *
     * ## 现行节奏
     * 1. **装机时一次**：装机向导（OneTimeConsent）显式引导确认一次，
     *    `CaptureConsent.markGranted` 记账——唯一一次主动授权；
     * 2. **日常零弹窗**：截屏与实时看屏首选无障碍通道（持久授权，无令牌）；
     * 3. **按需引导兜底**：投影类功能（录屏 / 无障碍不可用时的兜底）真正下发
     *    且无令牌时，才由 [dispatch] 给前台弹框或后台通知——此刻上下文明确
     *    （家长确实在等画面），是平台约束下唯一可接受的再授权时机。
     *    无障碍不可用的设备（服务启用早于能力版本）需在系统设置里关闭再
     *    打开无障碍服务一次，恢复后回到"日常零弹窗"。
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
        // notify() 在通知被关时静默丢弃且不抛异常，别把"调用过"当成"送达了"（R34）
        if (!NotificationDelivery.canDeliver(context, CHANNEL_ID)) {
            Logger.w(TAG) { "notifications disabled; capture consent notification withheld" }
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
        // fullScreenIntent：解锁后让系统优先把授权页直接顶到前台（在支持的系统上自动呈现，
        // Android 12+ 非通话类应用会降级为"点按查看"的悬浮提醒，不自动弹）。
        // 与 contentIntent 共用同一 PendingIntent，双保险：孩子不必先去通知栏翻找再点，
        // 解锁瞬间就能看到并确认屏幕采集授权（2026-10-21 修复"通知点不了"的体验兜底）。
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("护航守护")
            .setContentText(
                capturePromptText(CaptureConsent.requested(context), CaptureConsent.granted(context))
            )
            .setSmallIcon(context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_menu_gallery)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setOngoing(false)
            .setAutoCancel(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Logger.e(TAG, it) { "failed to post capture consent notification" } }
    }

    /**
     * 占用一次「打扰孩子」的额度。用单调钟 + 高水位，避免墙钟回拨让
     * `now - last` 恒为负、去抖窗口永不自愈（与端上其它短时窗同一口径）。
     */
    private fun claimPromptSlot(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        while (true) {
            val last = lastPromptAtMs.get()
            // 单调钟回拨 / 首次：重置高水位后放行，绝不把窗口永久钉死
            val tooSoon = last != Long.MIN_VALUE && last <= now && now - last < PROMPT_COOLDOWN_MS
            if (tooSoon) return false
            if (lastPromptAtMs.compareAndSet(last, now)) return true
        }
    }

    /**
     * 清掉引导通知。**刻意不复位节流**：本方法在授权成功与孩子点「取消」时都会被调用，
     * 若在此复位，孩子一取消就会被 8s 一次的服务端重发立刻再弹一次框。
     * 授权成功后 [ScreenCaptureSession.hasToken] 为真走静默分支，本就用不到节流额度。
     */
    fun clear(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }
}

/**
 * 引导通知正文（纯顶层函数，零 Android 依赖，便于 JVM 单测锁定）。
 *
 * ## 为什么必须区分"从没授权过"和"授权过但令牌失效"
 * MediaProjection 的凭据是**进程内**的 IBinder：进程被杀 / 平板重启后必然作废
 * （[CaptureConsentStore.loadProjectionConsent] 也只在同进程内允许复用），
 * 系统要求重新点一次"立即开始"——这是 Android 的隐私红线，Device Owner 也绕不过。
 * 旧文案对两种情况一律写"请确认屏幕采集授权"，家长就会认定"我装机时明明授权过了，
 * 怎么又说没授权"，反复回去重装/重授权也修不好。改成人话如实说明：这是重启后的
 * 重新确认，不是授权被清空、更不是装机没做。
 */
internal fun capturePromptText(everRequested: Boolean, everGranted: Boolean): String =
    if (everRequested && everGranted) {
        "平板重启后需重新确认屏幕采集授权"
    } else {
        "为实时守护做准备，请确认屏幕采集授权"
    }
