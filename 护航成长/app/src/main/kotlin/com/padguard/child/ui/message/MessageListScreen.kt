package com.padguard.child.ui.message

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.padguard.child.ui.components.Glyph
import com.padguard.child.ui.components.GlyphKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 消息中心列表。
 *
 * P0 数据源：复用 [LogRepository] 的拦截 / 告警事件。
 * P1 接入：家长推送消息 + 系统公告，与本列表用 section header 区分。
 */
@Composable
fun MessageListScreen(
    onItemClick: (String) -> Unit,
    viewModel: MessageListViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MessageListContent(
        state = state,
        onItemClick = onItemClick
    )
}

@Composable
private fun MessageListContent(
    state: MessageListUiState,
    onItemClick: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            Header(title = "消息", subtitle = subtitleFor(state.messages.size))
            if (state.messages.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        MessageRow(message = message, onClick = { onItemClick(message.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun MessageRow(message: MessageItemUi, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (message.isUnread) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Glyph(kind = GlyphKind.NOTICE)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message.title,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = message.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    maxLines = 2
                )
            }
            Text(
                text = formatTime(message.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "没有新消息",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "家长发的通知、审批结果都会出现在这里",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }
    }
}

private fun subtitleFor(count: Int): String = if (count == 0) "" else "共 $count 条通知"

private fun formatTime(millis: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - millis
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
        else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
    }
}
