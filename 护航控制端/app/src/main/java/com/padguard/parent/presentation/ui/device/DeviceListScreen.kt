package com.padguard.presentation.ui.device

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.padguard.presentation.ui.components.DeviceStatusCard
import com.padguard.presentation.viewmodel.HomeUiState

/**
 * 设备列表页（底部导航「设备」Tab）
 * 复用 DeviceStatusCard 展示每个被守护设备，点击进入设备详情
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    uiState: HomeUiState,
    onDeviceClick: (String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的设备") },
                actions = {
                    IconButton(onClick = { /* TODO: 扫码绑定新设备 */ }) {
                        Icon(Icons.Default.Add, contentDescription = "添加设备")
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.devices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无绑定设备",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(uiState.devices) { device ->
                    DeviceStatusCard(
                        device = device,
                        onClick = { onDeviceClick(device.id) }
                    )
                }
            }
        }
    }
}
