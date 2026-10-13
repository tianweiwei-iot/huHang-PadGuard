package com.padguard.presentation.ui.alert

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.padguard.domain.model.Alert
import com.padguard.domain.model.AlertLevel
import com.padguard.domain.model.AlertStatus
import com.padguard.presentation.ui.theme.PadGuardColors
import com.padguard.presentation.ui.components.AppSoftBackground
import com.padguard.presentation.ui.theme.PrimaryBlue
import com.padguard.presentation.ui.theme.PrimaryBlueDeep
import com.padguard.presentation.ui.theme.TextSecondary
import com.padguard.presentation.ui.theme.TintBlue
import com.padguard.presentation.viewmodel.UnlockCenterUiState
import com.padguard.presentation.viewmodel.UnlockTicketEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 消息中心（右上角「小铃铛」）。
 *
 * ## 为什么必须能翻出「已忽略」的记录
 * 忽略的语义是"我现在不想管"，不是"我不同意"。
 * 家长常常在忙的时候点忽略，回头想放行时如果记录已经消失，就只能等孩子重新提交一次——
 * 而孩子往往不会再提，这条申请就这么不了了之。
 * 所以这里把 PENDING / IGNORED / APPROVED / REJECTED 全部列出，非终态的一律保留处理入口。
 *
 * ## D7：为什么风险告警也进这个页面
 * 高危事件（权限被撤销 / 已 Root / 时间被篡改 / 无障碍被关闭）此前服务端有告警、
 * 家长端没有消费面。单独开一个"告警页"意味着家长要在铃铛之外再找第二个入口，
 * 而告警出现时家长恰恰在忙 —— 融进消息中心，一次点击全看到。
 * 告警分区排在解锁申请之上：前者是"管控可能已失效"（时效压力在家长侧），
 * 后者是"孩子在等"（时效压力在孩子侧），先看更危险的。
 *
 * ## 为什么状态与操作由外部传入而不是自己 hiltViewModel()
 * 本页与根层那个全局弹窗必须共享同一份数据（同一批工单、同一个轮询器）。
 * 若在导航目标里再取一次 ViewModel，拿到的是 NavBackStackEntry 作用域的新实例，
 * 两边数据各刷各的，会出现"弹窗刚忽略、消息中心还显示待处理"的撕裂。
 * 风险告警同理（[com.padguard.presentation.viewmodel.RiskAlertCenterViewModel]
 * 同样是根层全局实例），只是它没有配套弹窗，不需要防撕裂。
 */
@Composable
fun AlertCenterScreen(
    state: UnlockCenterUiState,
    onBack: () -> Unit,
    onApprove: (UnlockTicketEntry, Int) -> Unit,
    onReject: (UnlockTicketEntry) -> Unit,
    onRefresh: () -> Unit,
    // D7：风险告警分区（根层 RiskAlertCenterViewModel 驱动）
    riskAlerts: List<Alert> = emptyList(),
    riskUnreadCount: Int = 0,
    riskProcessing: Boolean = false,
    onAcknowledgeRisk: (Alert) -> Unit = {}
) {
    AppSoftBackground(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White)
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = PadGuardColors.TextPrimary)
                    }
                    Text(
                        text = "消息中心",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = PadGuardColors.TextPrimary
                    )
                    Spacer(Modifier.weight(1f))
                    // D7：红点合并两类未读（解锁工单 + 需关注的告警）——
                    // 家长认的是"铃铛上有东西要处理"，不关心来源。
                    val totalUnread = state.unreadCount + riskUnreadCount
                    if (totalUnread > 0) {
                        UnreadBadge(totalUnread)
                    }
                }
            }
        ) { padding ->
            if (state.records.isEmpty() && riskAlerts.isEmpty()) {
                EmptyState(modifier = Modifier.padding(padding).fillMaxSize())
                return@Scaffold
            }
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // D7：风险告警分区在前（管控失效比等放行更紧急）
                if (riskAlerts.isNotEmpty()) {
                    item(key = "risk_header") {
                        SectionHeader(title = "风险预警", count = riskAlerts.size)
                    }
                    items(riskAlerts, key = { "risk_${it.id}" }) { alert ->
                        RiskAlertCard(
                            alert = alert,
                            processing = riskProcessing,
                            onAcknowledge = { onAcknowledgeRisk(alert) }
                        )
                    }
                }
                if (state.records.isNotEmpty()) {
                    item(key = "ticket_header") {
                        SectionHeader(title = "解锁申请", count = state.records.size)
                    }
                    items(state.records, key = { it.ticket.id }) { entry ->
                        TicketCard(
                            entry = entry,
                            processing = state.processing,
                            onApprove = onApprove,
                            onReject = onReject
                        )
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

/** 分区标题：左文右数，让两类消息的来源一眼可分 */
@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = PadGuardColors.TextPrimary
        )
        Spacer(Modifier.width(6.dp))
        Text(text = "$count", fontSize = 12.sp, color = TextSecondary)
    }
}

/**
 * 风险告警卡片。
 *
 * 级别配色跟随内容语义（红=危急/橙=留意/灰=流水），不跟随任何"涨红跌绿"式的
 * 金融惯例 —— 这是告警不是行情。INFO 级不给确认按钮：它本就是"看过就算"的流水，
 * 让它占着一个待办动作反而稀释真正要处理的那些。
 */
@Composable
private fun RiskAlertCard(
    alert: Alert,
    processing: Boolean,
    onAcknowledge: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(levelTint(alert.level), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = levelColor(alert.level),
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = alert.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PadGuardColors.TextPrimary
                    )
                    Text(
                        text = "${alert.deviceName.ifBlank { "孩子的平板" }} · ${formatTime(alert.triggeredAt)}",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
                AlertStatusChip(alert.status)
            }

            if (alert.message.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(alert.message, fontSize = 14.sp, color = PadGuardColors.TextPrimary)
            }

            // 仅"未确认且非 INFO"给确认入口；已确认/已关闭的只留回看
            if (alert.needsAttention) {
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onAcknowledge,
                    enabled = !processing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryBlue,
                        contentColor = Color.White
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                ) {
                    Text(text = "确认收到", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            } else if (alert.status != AlertStatus.ACTIVE) {
                Spacer(Modifier.height(10.dp))
                Text("已确认", fontSize = 12.sp, color = TextSecondary)
            }
        }
    }
}

@Composable
private fun AlertStatusChip(status: AlertStatus) {
    val (text, bg, fg) = when (status) {
        AlertStatus.ACTIVE -> Triple("待确认", Color(0xFFFFEBEE), Color(0xFFC62828))
        AlertStatus.ACKNOWLEDGED -> Triple("已确认", Color(0xFFE8F5E9), Color(0xFF2E7D32))
        else -> Triple("已处理", Color(0xFFF1F5F9), Color(0xFF64748B))
    }
    Surface(shape = RoundedCornerShape(8.dp), color = bg) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            fontSize = 12.sp,
            color = fg,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun levelColor(level: AlertLevel): Color = when (level) {
    AlertLevel.CRITICAL -> Color(0xFFD32F2F)
    AlertLevel.WARNING -> Color(0xFFE65100)
    AlertLevel.INFO -> Color(0xFF64748B)
}

private fun levelTint(level: AlertLevel): Color = when (level) {
    AlertLevel.CRITICAL -> Color(0xFFFFEBEE)
    AlertLevel.WARNING -> Color(0xFFFFF3E0)
    AlertLevel.INFO -> Color(0xFFF1F5F9)
}

@Composable
private fun UnreadBadge(count: Int) {
    Box(
        modifier = Modifier
            .padding(end = 12.dp)
            .background(Color(0xFFFF3B30), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text = "未读 $count", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.NotificationsNone,
            contentDescription = null,
            tint = TextSecondary.copy(alpha = 0.5f),
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text("暂无消息", fontSize = 15.sp, color = TextSecondary)
        Spacer(Modifier.height(4.dp))
        Text("孩子提交解锁申请或设备出现异常时会出现在这里", fontSize = 13.sp, color = TextSecondary.copy(alpha = 0.7f))
    }
}

@Composable
private fun TicketCard(
    entry: UnlockTicketEntry,
    processing: Boolean,
    onApprove: (UnlockTicketEntry, Int) -> Unit,
    onReject: (UnlockTicketEntry) -> Unit
) {
    val ticket = entry.ticket
    // 选中态：与全局弹窗保持一致的"浅色初始 / 深色选中"反馈
    var selected: Choice? by remember(ticket.id) { mutableStateOf(null) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(TintBlue, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.LockOpen, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("解锁申请 · ${ticket.targetLabel}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = PadGuardColors.TextPrimary)
                    Text(
                        "${entry.deviceName.ifBlank { "孩子的平板" }} · ${formatTime(ticket.createdAt)}",
                        fontSize = 12.sp, color = TextSecondary
                    )
                }
                StatusChip(ticket.status)
            }

            if (!ticket.reason.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text("理由：${ticket.reason}", fontSize = 14.sp, color = PadGuardColors.TextPrimary)
            }
            ticket.durationMinutes?.let {
                Spacer(Modifier.height(4.dp))
                Text("申请时长：$it 分钟", fontSize = 13.sp, color = TextSecondary)
            }

            if (ticket.isActionable) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChoiceButton(
                        text = "拒绝",
                        selected = selected == Choice.Reject,
                        enabled = selected == null && !processing,
                        modifier = Modifier.weight(1f),
                        onClick = { selected = Choice.Reject; onReject(entry) }
                    )
                    ChoiceButton(
                        text = "同意放行",
                        selected = selected == Choice.Approve,
                        enabled = selected == null && !processing,
                        modifier = Modifier.weight(1f),
                        onClick = { selected = Choice.Approve; onApprove(entry, ticket.durationMinutes ?: 30) }
                    )
                }
            } else {
                Spacer(Modifier.height(10.dp))
                Text("该申请已处理完毕", fontSize = 12.sp, color = TextSecondary)
            }
        }
    }
}

private enum class Choice { Approve, Reject }

@Composable
private fun StatusChip(status: String) {
    val (text, bg, fg) = when (status) {
        "PENDING" -> Triple("待处理", Color(0xFFFFF3E0), Color(0xFFE65100))
        "IGNORED" -> Triple("已忽略", Color(0xFFF1F5F9), Color(0xFF64748B))
        "APPROVED" -> Triple("已同意", Color(0xFFE8F5E9), Color(0xFF2E7D32))
        "REJECTED" -> Triple("已拒绝", Color(0xFFFFEBEE), Color(0xFFC62828))
        else -> Triple(status, Color(0xFFF1F5F9), Color(0xFF64748B))
    }
    Surface(shape = RoundedCornerShape(8.dp), color = bg) {
        Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), fontSize = 12.sp, color = fg, fontWeight = FontWeight.Medium)
    }
}

/** 与全局弹窗一致的按钮配色：浅色初始，选中后深色 */
@Composable
private fun ChoiceButton(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(42.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) PrimaryBlue else TintBlue,
            contentColor = if (selected) Color.White else PrimaryBlueDeep,
            disabledContainerColor = if (selected) PrimaryBlue else TintBlue,
            disabledContentColor = if (selected) Color.White else PrimaryBlueDeep.copy(alpha = 0.5f)
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
    ) {
        Text(text, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

private fun formatTime(millis: Long): String =
    if (millis <= 0) "-" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
