package com.padguard.child.ui.profile

import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.padguard.child.permission.PermissionOnboardingViewModel
import com.padguard.core.data.model.policy.ControlMode

/**
 * 权限引导页。
 *
 * 不同 kind 对应不同引导文案与跳转入口：
 * - device_admin: 设备管理员（启动系统设备管理激活 / 已是基础管理员时给出设备所有者升级路径）
 * - usage_stats: 使用情况访问（跳 Settings 安全页面）
 *
 * ## P7 修复要点
 * 之前"去开启"无论当前状态都只发 `ACTION_ADD_DEVICE_ADMIN`：
 * 一旦基础设备管理员(DA)已激活，系统不再弹窗，按钮看起来"点了没反应"，
 * 孩子误以为授权失败。这里按真实权限状态分流：
 * 1. LEGACY（未激活）→ 真正拉起激活页（发 DA 意图），激活后才能锁屏/禁相机；
 * 2. DEVICE_ADMIN（仅基础管理员）→ 给出"升级为设备所有者(DO)"的合法路径
 *    （adb 命令 / 扫码预置，App 自身无法静默自提），并提供一键复制；
 * 3. DO/PO（已是所有者）→ 直接展示已激活，无需再操作。
 * 任何 startActivity 异常都通过 Toast 暴露，不再静默吞掉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionGuideScreen(
    kind: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val vm: PermissionOnboardingViewModel = hiltViewModel()
    val mode by vm.controlMode.collectAsStateWithLifecycle()
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
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = descriptionFor(kind, mode),
                    style = MaterialTheme.typography.bodyLarge
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        stepsFor(kind, mode).forEachIndexed { index, step ->
                            Text(
                                text = "${index + 1}. $step",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                if (kind == "device_admin") {
                    DeviceAdminActions(context = context, mode = mode, vm = vm)
                }

                if (kind == "usage_stats") {
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                                data = android.net.Uri.fromParts("package", context.packageName, null)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            runCatching { context.startActivity(intent) }
                                .onFailure { e ->
                                    Toast.makeText(context, "无法打开使用情况设置：${e.message}", Toast.LENGTH_LONG).show()
                                }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "去开启（使用情况访问）")
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceAdminActions(
    context: Context,
    mode: ControlMode,
    vm: PermissionOnboardingViewModel
) {
    when (mode) {
        ControlMode.LEGACY -> {
            Button(
                onClick = {
                    runCatching { context.startActivity(vm.deviceAdminIntent()) }
                        .onFailure { e ->
                            Toast.makeText(context, "无法打开系统激活页：${e.message}", Toast.LENGTH_LONG).show()
                        }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "去开启（激活设备管理员）")
            }
        }

        ControlMode.DEVICE_ADMIN -> {
            // 已是基础管理员：锁屏/禁相机可用，但 Kiosk/USB 限制/静默安装等需设备所有者(DO)
            Text(
                text = "当前为「基础设备管理员」：锁屏、禁用相机等已可用。如需 Kiosk 锁定、USB 数据限制、静默安装等最高权限，需升级为「设备所有者」。设备所有者必须由外部预置完成（App 无法自行提升），复制下方命令到电脑执行即可：",
                style = MaterialTheme.typography.bodyMedium
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = vm.provisioning.adbCommand,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            OutlinedButton(
                onClick = {
                    copyText(context, vm.provisioning.adbCommand, "激活命令已复制")
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "复制 adb 命令")
            }
            OutlinedButton(
                onClick = {
                    // 重新拉起激活页：用于确认/重置基础管理员状态
                    runCatching { context.startActivity(vm.deviceAdminIntent()) }
                        .onFailure { e ->
                            Toast.makeText(context, "无法打开系统激活页：${e.message}", Toast.LENGTH_LONG).show()
                        }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "重新打开系统激活页")
            }
        }

        else -> {
            // DO / PO 已激活：全部能力可用
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "已激活设备所有者，全部管控能力可用。无需进一步操作。",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private fun copyText(context: Context, text: String, toast: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("padguard", text))
    }
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}

private fun titleFor(kind: String): String = when (kind) {
    "device_admin" -> "设备管理员"
    "usage_stats" -> "使用情况访问"
    else -> "权限引导"
}

private fun descriptionFor(kind: String, mode: ControlMode): String = when (kind) {
    "device_admin" -> when (mode) {
        ControlMode.LEGACY -> "设备管理员是系统级权限，授予后本 App 才能锁定屏幕、禁用相机、防卸载等。"
        ControlMode.DEVICE_ADMIN -> "你已激活基础设备管理员。锁屏、禁用相机已可用；更高权限（Kiosk、USB 限制）需升级为设备所有者。"
        else -> "你已激活设备所有者，全部管控能力可用。"
    }
    "usage_stats" -> "使用情况访问是 Android 提供的统计权限，授予后我们才能读取每个 App 的真实使用时长。"
    else -> "请按提示完成权限开启。"
}

private fun stepsFor(kind: String, mode: ControlMode): List<String> = when (kind) {
    "device_admin" -> when (mode) {
        ControlMode.LEGACY -> listOf(
            "点击下方「去开启」",
            "在系统弹窗中选择「激活」",
            "返回本页面，“已开启”即表示成功"
        )
        ControlMode.DEVICE_ADMIN -> listOf(
            "基础权限已激活（可锁屏、禁相机）",
            "如需最高权限，复制下方 adb 命令到电脑执行",
            "执行后设备变为「设备所有者」，全部能力解锁"
        )
        else -> listOf("已激活设备所有者，无需额外操作。")
    }
    "usage_stats" -> listOf(
        "点击下方「去开启」",
        "在设置中找到「护航成长」",
        "打开「使用情况访问」开关",
        "返回本页面，“已开启”即表示成功"
    )
    else -> emptyList()
}
