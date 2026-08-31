package com.padguard.child.ui.unlock

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.padguard.core.data.repository.UnlockRequest

/**
 * 临时解锁申请页。
 *
 * 两个入口共用本页：
 * 1. 首页「申请临时解锁」入口 —— 不带包名，申请对象为"整机使用时间"；
 * 2. 应用拦截页「申请临时解锁」按钮 —— 带包名，直接锁定到那个应用。
 *
 * 交互刻意做得很短：选时长（三个档位）→ 点/填理由 → 提交。
 * 这个页面的用户是被拦下来、正着急的孩子，多一步输入就多一次放弃。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnlockRequestScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    prefillPackage: String = "",
    prefillLabel: String = "",
    viewModel: UnlockRequestViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(prefillPackage) {
        viewModel.setTarget(prefillPackage, prefillLabel)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("申请临时解锁") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                }
            )
        }
    ) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (state.hasPending) {
                    PendingNotice()
                } else if (state.justSubmitted) {
                    SubmittedNotice(onDismiss = viewModel::dismissSubmitted)
                }

                TargetCard(label = state.targetLabel)

                DurationCard(
                    selected = state.durationMinutes,
                    enabled = !state.hasPending,
                    onSelect = viewModel::setDuration
                )

                ReasonCard(
                    text = state.reasonText,
                    enabled = !state.hasPending,
                    onTextChange = viewModel::setReason
                )

                SubmitButton(
                    canSubmit = state.canSubmit,
                    submitting = state.submitting,
                    onSubmit = viewModel::submit
                )

                if (state.history.isNotEmpty()) {
                    HistoryCard(items = state.history)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun PendingNotice() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "已有一条申请正在等待家长处理",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Text(
                text = "家长同意后会自动放行，无需重复提交。若长时间没有回应，可以直接和家长说一声。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun SubmittedNotice(onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "申请已提交，等待家长处理",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}

@Composable
private fun TargetCard(label: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "申请对象",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun DurationCard(
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "需要多久",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UnlockRequestViewModel.DURATION_OPTIONS.forEach { minutes ->
                    FilterChip(
                        selected = selected == minutes,
                        enabled = enabled,
                        onClick = { onSelect(minutes) },
                        label = { Text("${minutes}分钟") }
                    )
                }
            }
            Text(
                text = "时长最终由家长决定，这里填的是你的期望值。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun ReasonCard(
    text: String,
    enabled: Boolean,
    onTextChange: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "申请理由（必填）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 只放前两个，横向排满会挤；剩下的靠手输更自由
                UnlockRequestViewModel.QUICK_REASONS.take(2).forEach { reason ->
                    SuggestionChip(
                        enabled = enabled,
                        onClick = { onTextChange(reason) },
                        label = { Text(reason) }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UnlockRequestViewModel.QUICK_REASONS.drop(2).forEach { reason ->
                    SuggestionChip(
                        enabled = enabled,
                        onClick = { onTextChange(reason) },
                        label = { Text(reason) }
                    )
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                placeholder = { Text("说清楚要做什么，家长更容易同意") }
            )
        }
    }
}

@Composable
private fun SubmitButton(
    canSubmit: Boolean,
    submitting: Boolean,
    onSubmit: () -> Unit
) {
    Button(
        onClick = onSubmit,
        enabled = canSubmit,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (submitting) {
            CircularProgressIndicator(
                modifier = Modifier.height(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            Text("提交申请")
        }
    }
}

@Composable
private fun HistoryCard(items: List<UnlockRequestItemUi>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "我的申请",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            items.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider()
                HistoryRow(item)
            }
        }
    }
}

@Composable
private fun HistoryRow(item: UnlockRequestItemUi) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${item.targetLabel} · ${item.durationLabel}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            StatusPill(item.status)
        }
        if (item.reasonText.isNotBlank()) {
            Text(
                text = item.reasonText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
        Text(
            text = item.timeLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun StatusPill(status: UnlockRequest.Status) {
    val (label, color) = when (status) {
        UnlockRequest.Status.PENDING -> "申请中" to Color(0xFFFFB74D)
        UnlockRequest.Status.APPROVED -> "已同意" to Color(0xFF66BB6A)
        UnlockRequest.Status.REJECTED -> "已拒绝" to Color(0xFFE57373)
        UnlockRequest.Status.EXPIRED -> "已过期" to Color(0xFF9E9E9E)
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = color
    )
}
