package com.padguard.parent.presentation.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.padguard.parent.presentation.ui.theme.PadGuardColors

/**
 * 高风险指令的统一二次确认（R-P3-2）。
 *
 * ## 为什么这几条指令必须多一步
 * 「一键锁屏」「暂停整机管控」这类操作**一触即发、而且影响是即时的**：
 * - 锁屏：孩子端当场进入锁定浮层，正在写作业/上课的平板直接不可用；
 * - 暂停整机管控：锁屏浮层收起、设置与应用拦截解除、DO 权限归还 —— 相当于"今晚不设防"，
 *   而且家长往往以为只是关掉一个小开关。
 *
 * 而这两个按钮在界面上都紧挨着别的常用操作（详情页快捷网格、策略页"一键解锁"旁边），
 * 误触成本远高于多一次点击。误触代价是"孩子突然被锁"或"管控悄悄失效"，两种情况都很难第一时间发现。
 *
 * ## 设计取舍
 * - **确认按钮用警示色**，取消按钮弱化为文本按钮：视觉权重把"取消"放得更轻，但不隐藏；
 * - 文案里点明**具体是哪台设备**、以及**会发生什么**，而不是抽象的"确定要执行吗"；
 * - 只给强干预类操作加这一步。解锁、定位、看屏这类"恢复/只读"操作不加，
 *   否则确认框会被训练成无脑点"确定"，反而失去拦截意义。
 */
data class HighRiskAction(
    val title: String,
    val message: String,
    val confirmText: String,
    val onConfirm: () -> Unit
)

/** 高风险动作的文案工厂：把"设备是谁、会发生什么"写死在文案里，避免各页各写一套。 */
object HighRiskActions {

    /** 远程锁屏：孩子端立即整机锁定 */
    fun lockScreen(deviceName: String?, onConfirm: () -> Unit) = HighRiskAction(
        title = "锁定${deviceName?.let { "「$it」" } ?: "孩子的平板"}？",
        message = "锁定后平板会立即进入锁屏状态，孩子无法继续使用，需由你在家长端解锁。" +
            "如果孩子正在上课或写作业，请确认这不是误触。",
        confirmText = "确认锁屏",
        onConfirm = onConfirm
    )

    /** 暂停整机管控：全部防护临时失效 */
    fun pauseManagement(deviceName: String?, onConfirm: () -> Unit) = HighRiskAction(
        title = "暂停整机管控？",
        message = "暂停后${deviceName?.let { "「$it」" } ?: "该设备"}将回到完全可用状态：" +
            "锁屏浮层收起、设置与应用拦截解除，孩子可自由使用与修改设置。" +
            "此操作适合「临时进系统设置」等场景，用完请及时恢复。",
        confirmText = "确认暂停",
        onConfirm = onConfirm
    )
}

/**
 * 二次确认弹窗宿主：[action] 为 null 时不渲染。
 *
 * 调用方只需持有 `var pending by remember { mutableStateOf<HighRiskAction?>(null) }`，
 * 需要确认时 `pending = HighRiskActions.lockScreen(name) { ... }` 即可。
 * 点击确认后先关弹窗再执行 —— 避免执行过程中弹窗仍挂在屏幕上、被连点两次下发两条指令。
 */
@Composable
fun HighRiskConfirmDialog(action: HighRiskAction?, onDismiss: () -> Unit) {
    val current = action ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(current.title) },
        text = { Text(current.message) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                current.onConfirm()
            }) { Text(current.confirmText, color = PadGuardColors.WarningRed) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
