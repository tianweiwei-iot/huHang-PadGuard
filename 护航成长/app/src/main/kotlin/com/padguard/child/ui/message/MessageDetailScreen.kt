package com.padguard.child.ui.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 消息详情。
 *
 * P0 阶段内容从拦截 / 告警日志查回来，结构上保留"标题 / 时间 / 正文 / 处置建议"四段。
 * P1 阶段若接入家长推送，原文 / 富文本能力在此扩展即可。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDetailScreen(
    messageId: String,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "消息详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
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
                    .systemBarsPadding()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = decodeId(messageId),
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "事件 ID：$messageId",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "如果想使用被限制的应用，可以在首页发起「申请临时解锁」",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "申请会发送给家长审批，审批通过后该应用可在一段时间内使用。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                    }
                }
                Text(
                    text = "查看时间：${formatNow()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
        }
    }
}

/**
 * P0 阶段 ID 形如 "RISK-1700000000000"，从中抽出时间戳做展示。
 * 真实实现应从持久化层按 ID 取详情。
 */
private fun decodeId(id: String): String {
    val parts = id.split('-')
    if (parts.size >= 2) {
        val ts = parts.last().toLongOrNull()
        if (ts != null) {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ts))
            return "事件时间 $time"
        }
    }
    return "事件详情"
}

private fun formatNow(): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
