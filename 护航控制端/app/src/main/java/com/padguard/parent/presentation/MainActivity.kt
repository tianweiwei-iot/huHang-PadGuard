package com.padguard.parent.presentation

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.padguard.parent.notification.NotificationHelper
import com.padguard.presentation.ui.PadGuardNavHost
import com.padguard.presentation.ui.theme.PadGuardTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 主 Activity - Compose 单 Activity 架构
 * 所有页面通过 Navigation Compose 管理
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // D1（L-PA-03）：家长端通知能力。此前 manifest 声明了 POST_NOTIFICATIONS 却零请求、零发送，
    // 家长没开 App 时"孩子求解锁 / 设备告警 / 订阅到期"完全零触达。这里补齐渠道与权限。
    @Inject
    lateinit var notificationHelper: NotificationHelper

    // 仅在 Android 13+ 且尚未授权时发起；系统会去重，用户拒绝后不再重复打扰。
    // 授权结果无需处理：未授权时 notifyUnlockRequest 内部静默返回。
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op：结果由 canPost() 在发送时兜底判断 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 渠道创建与授权无关，先确保渠道存在（Android 8+ 必须，否则通知不显示）
        notificationHelper.createChannel()
        enableEdgeToEdge()
        setContent {
            PadGuardTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PadGuardNavHost()
                }
            }
        }
    }

    /** 登录成功后由导航层调用：申请通知权限（若尚未授予）。 */
    fun requestNotificationPermissionIfNeeded() {
        if (notificationHelper.canPost()) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * D1：通知点击复用已在栈中的 MainActivity（SINGLE_TOP）时走这里。
     * 必须把新 Intent 设回 Activity，PadGuardNavHost 才能读到
     * [NotificationHelper.EXTRA_OPEN_ALERTS] 并导航到消息中心；
     * 否则用户点第二条通知时，读到的还是上一条的旧 extra。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
