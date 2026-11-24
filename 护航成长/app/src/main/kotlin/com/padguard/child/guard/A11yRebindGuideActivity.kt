package com.padguard.child.guard

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.padguard.child.ui.theme.PadGuardTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 操作闸门（无障碍服务）失效时的本地引导页（R-P0-2 缓解项）。
 *
 * ## 由 [A11yRebindNotifier] 经全屏通知系统代拉到前台
 * 当孩子/家长在设备端、而操作闸门已被关闭（设置里停用无障碍、或进程被杀后系统未重绑且非 DO 无法静默自愈），
 * 此页会立即顶到当前界面，明确告知：
 *  - 操作闸门已失效、应用管控被绕过；
 *  - **非 DO 形态的边界**（如实告知，不夸大）：设置里可强制停止 / 卸载本应用、更改系统时间，从而绕过管控；
 *  - 一键前往系统无障碍设置重新开启。
 *
 * 仅做"告知 + 引导重绑"，不替代家长端高危告警，也不假装能从系统层面根治非 DO 强停（那需要设备所有者部署）。
 */
@AndroidEntryPoint
class A11yRebindGuideActivity : ComponentActivity() {

    @Inject lateinit var a11yHealer: AccessibilityCapabilityHealer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 引导页已在前台，撤掉常驻通知，避免栏上重复留痕
        A11yRebindNotifier.cancelFrom(this)
        val silentHealEnabled = a11yHealer.canSilentHeal()
        setContent {
            PadGuardTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    A11yRebindGuide(
                        silentHealEnabled = silentHealEnabled,
                        onOpenSettings = {
                            runCatching {
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                            finish()
                        },
                        onDismiss = { finish() }
                    )
                }
            }
        }
    }
}

@Composable
private fun A11yRebindGuide(
    silentHealEnabled: Boolean,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "操作闸门已失效",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "「操作闸门（无障碍服务）」已被关闭，应用管控、卸载防护等软拦截当前全部失效，" +
                "孩子可在不受管控的情况下使用设备。",
            fontSize = 15.sp,
            lineHeight = 22.sp
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "常见成因：孩子在上滑锁屏 / 多任务界面点了「一键清理 / 清理加速」，" +
                "或省电策略停用了无障碍。" +
                if (silentHealEnabled) {
                    "本机已开启「清理后自动恢复」，守护会在 1~3 分钟内自行把开关写回；" +
                        "若本提示持续不消失，说明自动恢复失败（如系统拒绝写入），请先手动重开并联系装机人员核查。"
                } else {
                    "本机尚未开启「清理后自动恢复」：被清理后只能手动重开。" +
                        "装机时经 USB 执行一次 pm grant WRITE_SECURE_SETTINGS 授权即可开启自动恢复。"
                },
            fontSize = 14.sp,
            lineHeight = 21.sp
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "请注意设备防护边界：当前设备未以「设备所有者(DO)」模式部署，属于软管控。" +
                "在系统设置里，本应用可被强制停止、卸载，或更改系统时间，从而绕过管控。" +
                "开启无障碍只能恢复软拦截，无法从系统层面根治——要彻底锁死，建议把平板设为设备所有者。",
            fontSize = 14.sp,
            lineHeight = 21.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(28.dp))
        Button(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "前往设置 · 重新开启无障碍")
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "稍后处理")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "家长端已同步收到高危告警，可远程掌握此状态。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
