package com.padguard.child.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 本机应用台账页（「我的 → 应用管理」）。
 *
 * 只做**只读展示**：本端是被管控端，孩子不应拥有越过家长策略修改应用状态的能力。
 * 页面回答两个问题 —— 能不能打开（受策略名单约束）、今天能用多久（受单应用限额约束），
 * 其余一概不做。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppInventoryScreen(
    onBack: () -> Unit,
    viewModel: AppInventoryViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("应用管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        when {
            !state.loaded -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            error != null -> Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = error.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        text = "共 ${state.items.size} 个应用，其中 ${state.items.count { it.restricted }} 个受限制。" +
                            "策略由家长在管控端设置，本页仅供查看。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                items(state.items, key = { it.packageName }) { row ->
                    AppRow(row)
                }
            }
        }
    }
}

@Composable
private fun AppRow(row: AppInventoryRow) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = row.appName, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = row.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
                val limitText = buildLimitText(row)
                if (limitText.isNotEmpty()) {
                    Text(
                        text = limitText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    )
                }
            }
            StatusChip(restricted = row.restricted)
        }
    }
}

@Composable
private fun StatusChip(restricted: Boolean) {
    val (text, color) = if (restricted) {
        "受限" to MaterialTheme.colorScheme.error
    } else {
        "可用" to MaterialTheme.colorScheme.primary
    }
    Text(text = text, style = MaterialTheme.typography.labelMedium, color = color)
}

private fun buildLimitText(row: AppInventoryRow): String {
    val parts = mutableListOf<String>()
    if (row.dailyMinutes > 0) parts += "每日 ${row.dailyMinutes} 分钟"
    if (row.singleMinutes > 0) parts += "单次 ${row.singleMinutes} 分钟"
    return parts.joinToString(" · ")
}
