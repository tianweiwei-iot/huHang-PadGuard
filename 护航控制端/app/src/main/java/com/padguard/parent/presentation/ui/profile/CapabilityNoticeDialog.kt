package com.padguard.parent.presentation.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 管控能力说明弹窗（P0-1 家长须知·降级边界）。
 *
 * 触发：关于页「管控能力说明（重要）」入口。属于**告知性**弹窗（非授权前置），
 * 因此允许点外部/返回关闭（与 [com.padguard.parent.presentation.ui.auth.AgreementDialog]
 * 的"不可跳过"语义不同）。
 *
 * 文案须与服务端/孩子端已实现的边界一致，且如实声明软管控守不住的设备级脱离面：
 * 卸载、系统设置强停、改系统时间、安全模式/恢复出厂、关闭无障碍服务。
 */
@Composable
fun CapabilityNoticeDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "管控能力说明（重要）",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "本应用通过「设备管理员 + 无障碍服务」在本地守护孩子的使用时长与时段。" +
                        "请家长务必了解以下边界，避免误判：",
                    style = MaterialTheme.typography.bodyMedium
                )
                Bullet(
                    "需要保持应用在后台运行、且无障碍服务开启。若孩子在系统设置中「强制停止」本应用、" +
                        "关闭无障碍服务、或关机重启后未重新打开，管控会暂时失效，直到应用再次运行——" +
                        "这属于系统安全机制，软件无法绕过。"
                )
                Bullet(
                    "修改系统时间可干扰「时段」判定（例如把表改到允许时段以逃避锁屏），但无法抹掉" +
                        "「今日已用时长」，时长额度仍会按真实流逝计算。请勿让孩子随意更改系统时间；" +
                        "如怀疑被篡改，可在家长端手动「重新同步策略」。"
                )
                Bullet(
                    "卸载 / 恢复出厂 / 安全模式会解除本地管控，属设备级操作，软件无法阻止；" +
                        "如需更强约束，请在支持的设备上开启「设备所有者（Device Owner）」模式。"
                )
                Bullet(
                    "看屏（截图 / 录屏）需孩子授权 MediaProjection；孩子可拒绝授权，此时降级为" +
                        "「不采集」而非崩溃。授权状态会在家长端展示。"
                )
                Bullet(
                    "网络中断时，时长与时段管控仍在本地生效；恢复网络后服务端会校正状态。"
                )
                Text(
                    text = "我们将持续通过系统能力加固上述边界，并在能力升级时同步更新本说明。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("我已知晓")
            }
        }
    )
}

@Composable
private fun Bullet(text: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "• $text",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
