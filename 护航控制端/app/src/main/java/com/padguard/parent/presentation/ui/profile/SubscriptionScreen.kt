package com.padguard.parent.presentation.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CardMembership
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.padguard.parent.data.model.PlanDto
import com.padguard.parent.data.model.SubscriptionDto
import com.padguard.parent.presentation.ui.components.SoftCard
import com.padguard.parent.data.model.PayChannelDto
import com.padguard.parent.presentation.viewmodel.CheckoutState
import com.padguard.parent.presentation.viewmodel.SubscriptionUiState
import com.padguard.parent.presentation.viewmodel.SubscriptionViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 订阅与续费页。
 *
 * 页面顶部必须先回答家长最关心的两个问题——「现在还能不能用」「什么时候到期」，
 * 再放套餐列表。反过来的话，家长在不知道剩余时长的情况下很难决定买哪一档。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionScreen(
    onBack: () -> Unit,
    viewModel: SubscriptionViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // 错误用 Snackbar 而不是占位文案：下单失败时页面仍能看到套餐，
    // 否则一次网络抖动就把整个页面变成一片空白，家长只会反复退出重进。
    LaunchedEffect(state.error, state.message) {
        val text = state.error ?: state.message
        if (text != null) {
            snackbar.showSnackbar(text)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("订阅与续费") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.loading && state.subscription == null) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("正在查询订阅状态…", style = MaterialTheme.typography.bodyMedium)
                }
                return@Scaffold
            }

            state.subscription?.let { StatusCard(it) }

            Text(
                "选择套餐",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            state.plans.forEach { plan -> PlanCard(plan = plan, state = state, onBuy = viewModel::startCheckout) }

            if (state.orders.isNotEmpty()) {
                Text(
                    "订单记录",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                state.orders.forEach { OrderRow(it) }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "支付渠道正在接入中。下单后请保留订单号，联系客服完成支付即可自动开通；" +
                    "已付费未到账时客服也可凭订单号手动开通。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }

    // 结算页：选中套餐后弹出收银台（金额 + 多渠道 + 示例支付）
    state.checkout?.let { checkout ->
        CheckoutSheet(
            checkout = checkout,
            onSelectChannel = viewModel::selectChannel,
            onConfirm = viewModel::confirmPay,
            onDismiss = viewModel::cancelCheckout
        )
    }
}

@Composable
private fun StatusCard(sub: SubscriptionDto) {
    val active = sub.active
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (active) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        !active -> "订阅已到期，续费后恢复使用"
                        sub.plan == "PERMANENT" -> "永久会员 · 已开通"
                        sub.paid -> "已订阅 · ${sub.planLabel}"
                        else -> "免费试用中"
                    },
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    !active -> "已于 ${formatDate(sub.expiredAt)} 到期"
                    sub.plan == "PERMANENT" -> "永久有效，无到期时间"
                    else -> "剩余 ${sub.remainDays} 天（有效期至 ${formatDate(sub.expiredAt)}）"
                },
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "已绑定 ${sub.deviceUsed} / ${sub.deviceLimit} 台设备",
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun PlanCard(
    plan: PlanDto,
    state: SubscriptionUiState,
    onBuy: (PlanDto) -> Unit
) {
    val current = state.subscription?.plan == plan.key && state.subscription?.paid == true
    SoftCard(modifier = Modifier.fillMaxWidth(), onClick = { if (!current) onBuy(plan) }) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.CardMembership, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(plan.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    "可绑定 ${plan.deviceLimit} 台设备" +
                        if (plan.months > 1) " · 折合 ¥${plan.amountFen / plan.months / 100}/月" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    plan.priceText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (current) {
                    Text("当前套餐", style = MaterialTheme.typography.bodySmall)
                } else if (state.orderingPlan == plan.key) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp))
                } else {
                    OutlinedButton(onClick = { onBuy(plan) }) { Text("订阅") }
                }
            }
        }
    }
}

@Composable
private fun OrderRow(order: com.padguard.parent.data.model.OrderDto) {
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("${order.planLabel} · ${order.priceText}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${order.orderNo} · ${formatDate(order.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Text(
                when (order.status) {
                    "PAID" -> "已支付"
                    "PENDING" -> "待支付"
                    "CLOSED" -> "已关闭"
                    "REFUNDED" -> "已退款"
                    else -> order.status
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (order.status == "PAID") MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

private fun formatDate(ts: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ts))

/**
 * 收银台弹窗。
 *
 * 渲染 [CheckoutState]：顶部金额卡片 → 可勾选的支付渠道列表（RadioButton）→ 确认支付按钮。
 * 业务逻辑全在 [com.padguard.parent.presentation.viewmodel.SubscriptionViewModel]，这里只管展示与交互。
 *
 * - 未选渠道 / 支付中时，"确认支付"禁用，避免重复提交；
 * - 支付中显示进度环 + "支付中…"，取消按钮同步禁用，防止支付中途被关掉；
 * - 结算页级错误（下单/示例支付失败）只盖住弹窗，不污染整页，家长仍能看到套餐。
 */
@Composable
private fun CheckoutSheet(
    checkout: CheckoutState,
    onSelectChannel: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = checkout.selectedChannel != null && !checkout.paying
            ) {
                if (checkout.paying) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp).width(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (checkout.paying) "支付中…" else "确认支付 ${checkout.plan.priceText}")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, enabled = !checkout.paying) {
                Text("取消")
            }
        },
        title = { Text("收银台 · ${checkout.plan.label}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            "应付金额",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            checkout.plan.priceText,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "${checkout.plan.months} 个月 · 可绑定 ${checkout.plan.deviceLimit} 台设备",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    "选择支付渠道",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                checkout.channels.forEach { ch ->
                    val selected = ch.key == checkout.selectedChannel
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !checkout.paying) { onSelectChannel(ch.key) }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = { onSelectChannel(ch.key) },
                            enabled = !checkout.paying
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ch.label, style = MaterialTheme.typography.bodyLarge)
                            val note = ch.note
                            if (!note.isNullOrBlank()) {
                                Text(
                                    note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }

                checkout.error?.let { err ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    )
}
