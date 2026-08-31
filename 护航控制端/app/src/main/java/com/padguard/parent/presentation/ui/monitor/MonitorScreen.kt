package com.padguard.presentation.ui.monitor

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.padguard.presentation.ui.theme.PadGuardColors
import com.padguard.presentation.viewmodel.MonitorViewModel
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.*

/**
 * 实时监控 · 查看屏幕（纯家庭场景）
 * 展示实时画面占位（Mock）、实时位置，支持截屏与刷新
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen(
    deviceId: String,
    onBack: () -> Unit,
    viewModel: MonitorViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.uiState.collectLatest {
            if (it.toast != null) { snackbarHostState.showSnackbar(it.toast); viewModel.clearToast() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("实时屏幕") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 实时画面占位
            Card(
                modifier = Modifier.fillMaxWidth().height(280.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E))
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier.size(64.dp)
                                .background(
                                    brush = Brush.linearGradient(listOf(Color(0xFF4A90D9), Color(0xFF07C160))),
                                    shape = RoundedCornerShape(16.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ScreenShare, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("实时画面", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        val time = uiState.screenshot?.capturedAt?.let {
                            SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(it))
                        } ?: "--:--:--"
                        Text("更新于 $time", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                    }
                    Surface(
                        modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                        color = PadGuardColors.WarningRed,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("LIVE", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { viewModel.captureScreenshot() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("截屏")
                }
                OutlinedButton(
                    onClick = { viewModel.load() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("刷新")
                }
            }

            val monitorLocation = uiState.location
            if (monitorLocation != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = PadGuardColors.OnlineGreen, modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("实时位置", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                            Text(monitorLocation.address ?: "未知地址", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
