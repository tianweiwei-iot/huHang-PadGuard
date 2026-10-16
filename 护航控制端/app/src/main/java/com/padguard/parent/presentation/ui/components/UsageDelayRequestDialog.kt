package com.padguard.parent.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.padguard.parent.presentation.ui.theme.PadGuardColors
import com.padguard.parent.presentation.ui.theme.PrimaryBlue
import com.padguard.parent.presentation.ui.theme.PrimaryBlueDeep
import com.padguard.parent.presentation.ui.theme.TextSecondary
import com.padguard.parent.presentation.ui.theme.TintBlue
import com.padguard.parent.presentation.viewmodel.UsageDelayTicketEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 延时申请全局弹窗。
 *
 * ## 为什么挂在根导航层而不是某个页面里
 * 与 [UnlockRequestDialog] 同理由：这是"无论家长处于哪个界面都必须看见"的打扰式交互。
 * 孩子在计时倒计时页点了「延时申请」，家长可能在统计页 / 策略页，
 * 挂在页面内就等于收不到，孩子干等。
 *
 * ## 三个动作
 * - 「同意延长」：服务端按工单分钟数累加额度，孩子端下一次会话刷新即生效；
 * - 「拒绝」：关闭工单并给孩子下发「申请未通过」；
 * - 「忽略」：归档进消息中心，不下发消息给孩子，且工单仍可被批准（与解锁的"忽略"同语义）。
 *
 * 之所以把"拒绝"和"忽略"分开：拒绝会明确告诉孩子"没批"，忽略只是"现在不想管"，
 * 孩子端收到的信息不同，家长稍后还能在消息中心改主意批准。
 */
@Composable
fun UsageDelayRequestDialog(
    entry: UsageDelayTicketEntry,
    processing: Boolean = false,
    onIgnore: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    // 选中态：null 表示尚未选择；一旦选择就不再接受第二次点击（防连点重复下发）
    var selected: DelaySelection? by remember(entry.ticket.id) { mutableStateOf(null) }

    Dialog(
        onDismissRequest = {
            // 不允许点击外部关闭：必须做出决定的打扰式交互，随手一点就消失等于没提醒，
            // 孩子仍在倒计时页等着审批结果。
        },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(TintBlue),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        tint = PrimaryBlue,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = "延时申请",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = PadGuardColors.TextPrimary
                )
                Spacer(Modifier.height(12.dp))

                InfoRow("申请设备", entry.deviceName.ifBlank { "孩子的平板" })
                InfoRow("申请时长", "${entry.ticket.minutes} 分钟")
                InfoRow("申请时间", formatTime(entry.ticket.createdAt))

                if (!entry.ticket.reason.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "理由：${entry.ticket.reason}",
                        fontSize = 14.sp,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DelayChoiceButton(
                        text = "拒绝",
                        selected = selected == DelaySelection.Reject,
                        enabled = selected == null && !processing,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            selected = DelaySelection.Reject
                            onReject()
                        }
                    )
                    DelayChoiceButton(
                        text = "忽略",
                        selected = selected == DelaySelection.Ignore,
                        enabled = selected == null && !processing,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            selected = DelaySelection.Ignore
                            onIgnore()
                        }
                    )
                    DelayChoiceButton(
                        text = "同意延长",
                        selected = selected == DelaySelection.Approve,
                        enabled = selected == null && !processing,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            selected = DelaySelection.Approve
                            onApprove()
                        }
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (processing) "处理中…" else "拒绝会告知孩子未通过；忽略仅暂存，可在消息中心继续处理",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private enum class DelaySelection { Reject, Ignore, Approve }

/**
 * 选项按钮：浅色底为初始态，选中后深色底。
 * 用 `Button` 而不是自定义 Box：自带 minimumInteractiveSize(48dp) 与无障碍语义。
 */
@Composable
private fun DelayChoiceButton(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val container = if (selected) PrimaryBlue else TintBlue
    val content = if (selected) Color.White else PrimaryBlueDeep

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = if (selected) PrimaryBlue else TintBlue,
            disabledContentColor = if (selected) Color.White else PrimaryBlueDeep.copy(alpha = 0.5f)
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
    ) {
        Text(
            text = text,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 14.sp, color = TextSecondary)
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = PadGuardColors.TextPrimary
        )
    }
}

private fun formatTime(millis: Long): String =
    if (millis <= 0) "-" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
