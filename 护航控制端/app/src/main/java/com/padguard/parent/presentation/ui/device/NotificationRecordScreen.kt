package com.padguard.parent.presentation.ui.device

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.padguard.parent.presentation.ui.components.SoftCard
import com.padguard.parent.presentation.util.TimeFormat
import com.padguard.parent.presentation.viewmodel.NotificationRecordItem
import com.padguard.parent.presentation.viewmodel.NotificationRecordViewModel
import com.padguard.parent.presentation.viewmodel.NotificationRecordUiState

/**
 * 通知记录页（A2 家长端展示）。
 *
 * 展示孩子端代收的系统通知（仅包名 + 标题，不含正文）。列表按服务端返回的倒序展示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationRecordScreen(
    deviceId: String,
    onBack: () -> Unit,
    viewModel: NotificationRecordViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(deviceId) {
        if (deviceId.isNotEmpty()) viewModel.load(deviceId)
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "通知记录",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when {
                uiState.isLoading ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                uiState.error != null ->
                    Text(
                        text = uiState.error ?: "加载失败",
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp)
                    )

                uiState.items.isEmpty() ->
                    Text(
                        text = "暂无通知记录（孩子端需在系统设置中授权「通知使用权」）",
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp)
                    )

                else -> NotificationList(uiState)
            }
        }
    }
}

@Composable
private fun NotificationList(uiState: NotificationRecordUiState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(uiState.items, key = { it.id ?: it.packageName + it.timestamp }) { item ->
            NotificationRow(item)
        }
    }
}

@Composable
private fun NotificationRow(item: NotificationRecordItem) {
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = item.title.ifBlank { "（无标题）" },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            item.timestamp?.let { ts ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = TimeFormat.formatDateTimeFromMillis(ts),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                )
            }
        }
    }
}
