package com.padguard.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.padguard.presentation.ui.theme.PadGuardColors
import com.padguard.presentation.ui.theme.PrimaryBlue
import com.padguard.presentation.ui.theme.PrimaryBlueDeep
import com.padguard.presentation.ui.theme.TextSecondary
import com.padguard.presentation.ui.theme.TintBlue
import com.padguard.presentation.viewmodel.UnlockTicketEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 解锁申请全局弹窗。
 *
 * ## 为什么挂在根导航层而不是某个页面里
 * 这是"无论家长处于哪个界面都必须看见"的打扰式交互。
 * 挂在页面内就等于只有那个页面能看见，家长在统计页/策略页时完全收不到，
 * 孩子在锁屏页干等 —— 申请的时效性也就随之失效。
 *
 * ## 按钮配色约定
 * 初始为**浅色底**（淡蓝 + 蓝字），点击选中后变**深色底**（品牌蓝 + 白字）。
 * 之所以要"选中态"而不是纯按压态：家长需要明确知道"我刚才点的是哪一个"，
 * 尤其在弱网下请求要转一两秒，没有反馈会让人以为没点上而连点。
 *
 * ## 为什么两个按钮的处理结果都是"关掉弹窗"
 * 忽略 → 归档进消息中心；去处理 → 直接同意放行。
 * 两条路都是"处理完成"，弹窗理应退出，否则家长会以为还没生效而反复操作。
 */
@Composable
fun UnlockRequestDialog(
    entry: UnlockTicketEntry,
    processing: Boolean = false,
    onIgnore: () -> Unit,
    onHandle: () -> Unit
) {
    // 选中态：null 表示尚未选择；一旦选择就不再接受第二次点击（防连点重复下发）
    var selected: Selection? by remember(entry.ticket.id) { mutableStateOf(null) }

    Dialog(
        onDismissRequest = {
            // 不允许点击外部关闭：这是必须做出决定的打扰式交互，
            // 随手一点就消失等于没提醒，孩子仍在锁屏页等着。
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
                        Icons.Default.LockOpen,
                        contentDescription = null,
                        tint = PrimaryBlue,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = "解锁申请",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = PadGuardColors.TextPrimary
                )
                Spacer(Modifier.height(12.dp))

                InfoRow("申请设备", entry.deviceName.ifBlank { "孩子的平板" })
                InfoRow("申请对象", entry.ticket.targetLabel)
                entry.ticket.durationMinutes?.let {
                    InfoRow("申请时长", "$it 分钟")
                }
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
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ChoiceButton(
                        text = "忽略",
                        selected = selected == Selection.Ignore,
                        enabled = selected == null && !processing,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            selected = Selection.Ignore
                            onIgnore()
                        }
                    )
                    ChoiceButton(
                        text = "去处理",
                        selected = selected == Selection.Handle,
                        enabled = selected == null && !processing,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            selected = Selection.Handle
                            onHandle()
                        }
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (processing) "处理中…" else "忽略后可在右上角消息中心继续处理",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private enum class Selection { Ignore, Handle }

/**
 * 选项按钮：浅色底为初始态，选中后深色底。
 *
 * 用 `Button` 而不是自定义 Box：Button 自带 minimumInteractiveSize(48dp) 与无障碍语义，
 * 自己画容易漏掉触摸目标尺寸，在平板/老人机场景下点不准。
 */
@Composable
private fun ChoiceButton(
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
            fontSize = 16.sp,
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
