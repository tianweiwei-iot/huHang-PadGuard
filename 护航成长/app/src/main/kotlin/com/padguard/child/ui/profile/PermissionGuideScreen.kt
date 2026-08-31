package com.padguard.child.ui.profile

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * 权限引导页。
 *
 * 不同 kind 对应不同引导文案与跳转入口：
 * - device_admin: 设备管理员（启动系统设备管理激活）
 * - usage_stats: 使用情况访问（跳 Settings 安全页面）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionGuideScreen(
    kind: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = titleFor(kind)) },
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
                    text = descriptionFor(kind),
                    style = MaterialTheme.typography.bodyLarge
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        stepsFor(kind).forEachIndexed { index, step ->
                            Text(
                                text = "${index + 1}. $step",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                Button(
                    onClick = { openSystemScreen(context, kind) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "去开启")
                }
            }
        }
    }
}

private fun titleFor(kind: String): String = when (kind) {
    "device_admin" -> "设备管理员"
    "usage_stats" -> "使用情况访问"
    else -> "权限引导"
}

private fun descriptionFor(kind: String): String = when (kind) {
    "device_admin" -> "设备管理员是系统级权限，授予后本 App 才能锁定屏幕、隐藏状态栏、禁用相机等。"
    "usage_stats" -> "使用情况访问是 Android 提供的统计权限，授予后我们才能读取每个 App 的真实使用时长。"
    else -> "请按提示完成权限开启。"
}

private fun stepsFor(kind: String): List<String> = when (kind) {
    "device_admin" -> listOf(
        "点击下方「去开启」",
        "在系统弹窗中选择「激活」",
        "返回本页面，“已开启”即表示成功"
    )
    "usage_stats" -> listOf(
        "点击下方「去开启」",
        "在设置中找到「护航成长」",
        "打开「使用情况访问」开关",
        "返回本页面，“已开启”即表示成功"
    )
    else -> emptyList()
}

private fun openSystemScreen(context: Context, kind: String) {
    val intent: Intent = when (kind) {
        "device_admin" -> Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            val component = android.content.ComponentName(
                context,
                "com.padguard.child.receiver.PadGuardDeviceAdminReceiver"
            )
            putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
            putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "激活后可启用锁屏、应用管控等基础能力")
        }
        "usage_stats" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
