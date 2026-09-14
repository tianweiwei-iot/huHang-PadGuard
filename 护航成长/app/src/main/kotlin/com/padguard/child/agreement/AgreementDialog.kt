package com.padguard.child.agreement

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 使用授权协议弹窗（说明书 §5）。
 *
 * 触发时机：绑定成功后、自动授予权限前，模态展示，不可后台跳过（点外部不关闭）。
 * 同意 → 调 [AgreementViewModel.agree]（自动授予全部权限）；
 * 暂不同意 → [AgreementViewModel.decline]，进入受限预览模式。
 */
@Composable
fun AgreementDialog(
    onAgree: () -> Unit,
    onDecline: () -> Unit,
    granting: Boolean = false
) {
    AlertDialog(
        // 不可后台跳过：协议是合规前置条件，点外部不关闭
        onDismissRequest = { },
        title = {
            Text(
                text = "使用授权协议",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    text = AGREEMENT_TEXT,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Start
                )
            }
        },
        confirmButton = {
            Button(onClick = onAgree, enabled = !granting) {
                Text(text = if (granting) "正在授权…" else "同意并授权")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDecline, enabled = !granting) {
                Text(text = "暂不同意")
            }
        }
    )
}

/**
 * 入口门禁承载：仅当「已绑定但未同意」时由 [com.padguard.child.MainActivity.EntryRouter] 挂载。
 * [agreed] 翻转后由上层停止挂载，弹窗随之收起。
 */
@Composable
fun AgreementDialogHost() {
    val vm: AgreementViewModel = hiltViewModel()
    val granting by vm.granting.collectAsStateWithLifecycle()
    AgreementDialog(
        onAgree = vm::agree,
        onDecline = vm::decline,
        granting = granting
    )
}

/**
 * 协议正文（说明书 §5.2 要点）。
 * 必须明示授权项：设备信息、位置、应用使用统计与管控、通信（短信/通知/通话）、
 * 摄像头与麦克风、存储读写、远程控制（锁屏/重启/关机/丢失模式/恢复出厂）、网络与通知。
 */
private const val AGREEMENT_TEXT = """
为保障家长（管控端）对设备的远程守护能力，您同意后，本应用将获取并使用被管控端的全部权限，包括但不限于：

• 设备信息读取：型号、系统版本、序列号（脱敏）、存储、电池、网络状态；
• 位置信息：GPS / 基站 / WiFi 实时定位与历史轨迹；
• 应用使用统计与管控：安装、卸载、冻结、隐藏、时长与时段限制；
• 通信相关：读取 / 发送短信、通知代收、通话记录读取（按系统授权范围）；
• 摄像头与麦克风：姿态 / 距离监测、远程截屏 / 录屏 / 实时画面；
• 存储读写：日志、截图、下发文件；
• 远程控制：锁屏、重启、关机、丢失模式、恢复出厂（需家长二次授权）；
• 网络与通知：公告广播、消息推送。

所有数据经加密上传至护航服务端，仅绑定之管控端可查看与操作。设备将进入未成年人守护模式，部分系统能力（恢复出厂、USB 调试、未知来源安装）将被限制，需管控端授权方可解除。
"""
