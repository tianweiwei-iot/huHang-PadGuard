package com.padguard.core.transport.mqtt

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import com.padguard.core.common.Logger
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttPingSender
import org.eclipse.paho.client.mqttv3.internal.ClientComms

/**
 * 基于 AlarmManager 的 MQTT 心跳发送器。
 *
 * **为什么必须自己实现**：Paho 纯 Java 客户端默认用 `TimerPingSender`（java.util.Timer），
 * 而 Timer 在 Android Doze / App Standby 下会被冻结 —— 设备息屏十几分钟后心跳就停了，
 * Broker 端 KeepAlive 超时把设备判为离线，管控端看到一片"离线"却毫无异常日志，
 * 这是 MQTT 方案在 Android 上最常见的坑。
 *
 * 解法与官方 `org.eclipse.paho.android.service.AlarmPingSender` 一致（Apache-2.0）：
 * 用 `setExactAndAllowWhileIdle` 在 Doze 白名单窗口里唤醒进程发 PINGREQ，
 * 并在广播回调期间持有 WakeLock，确保网络 IO 完成前 CPU 不再睡回去。
 *
 * 与原版的两点差异：
 * 1. PendingIntent 强制加 `FLAG_IMMUTABLE`（targetSdk >= 31 硬性要求，原版因此在新系统崩溃）；
 * 2. 广播接收器用 `RECEIVER_NOT_EXPORTED` 注册（targetSdk >= 34 硬性要求）。
 */
class AlarmPingSender(private val context: Context) : MqttPingSender {

    private var comms: ClientComms? = null
    private var action: String = ""
    private var pendingIntent: PendingIntent? = null
    private var receiver: BroadcastReceiver? = null

    @Volatile
    private var started = false

    private val alarmManager: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override fun init(comms: ClientComms) {
        this.comms = comms
        this.action = "$ACTION_PREFIX.${comms.client.clientId}"
        this.receiver = PingReceiver()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun start() {
        val comms = this.comms ?: return
        val receiver = this.receiver ?: return

        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        pendingIntent = PendingIntent.getBroadcast(
            context,
            PING_REQUEST_CODE,
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        started = true
        schedule(comms.keepAlive)
        Logger.d(TAG) { "ping sender started, keepAlive=${comms.keepAlive}ms" }
    }

    override fun stop() {
        if (!started) return
        started = false
        pendingIntent?.let { runCatching { alarmManager.cancel(it) } }
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        Logger.d(TAG) { "ping sender stopped" }
    }

    override fun schedule(delayInMilliseconds: Long) {
        val intent = pendingIntent ?: return
        if (!started) return
        val triggerAt = System.currentTimeMillis() + delayInMilliseconds
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // setExactAndAllowWhileIdle 是唯一能在 Doze 下按时触发的接口，
                // 系统对其有 9 分钟最小间隔限流，因此 KeepAlive 不应低于 60s。
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, intent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, intent)
            }
        }.onFailure {
            Logger.w(TAG, it) { "failed to schedule mqtt ping" }
        }
    }

    private inner class PingReceiver : BroadcastReceiver() {

        override fun onReceive(ctx: Context, intent: Intent) {
            val powerManager = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
            wakeLock.acquire(WAKELOCK_TIMEOUT_MS)

            val token: IMqttToken? = comms?.checkForActivity(object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    releaseQuietly(wakeLock)
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Logger.w(TAG, exception) { "mqtt ping failed, awaiting auto-reconnect" }
                    releaseQuietly(wakeLock)
                }
            })

            if (token == null) {
                // checkForActivity 返回 null 表示当前无需 ping（连接已断或有其它在途报文）
                releaseQuietly(wakeLock)
            }
        }

        private fun releaseQuietly(wakeLock: PowerManager.WakeLock) {
            runCatching { if (wakeLock.isHeld) wakeLock.release() }
        }
    }

    companion object {
        private const val TAG = "AlarmPingSender"
        private const val ACTION_PREFIX = "com.padguard.child.mqtt.PING"
        private const val WAKELOCK_TAG = "PadGuard:MqttPing"
        private const val PING_REQUEST_CODE = 0x9101

        /** 单次 ping 的 CPU 保持上限，防止异常路径下 WakeLock 泄漏拖垮电池 */
        private const val WAKELOCK_TIMEOUT_MS = 30_000L
    }
}
