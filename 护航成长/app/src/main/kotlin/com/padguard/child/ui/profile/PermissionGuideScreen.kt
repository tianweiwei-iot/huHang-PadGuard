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
import com.padguard.child.permission.PermissionGuideViewModel
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
    val vm: PermissionGuideViewModel = hiltViewModel()
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

                // D7（决策 D4 联动）：能力分层明示。
                // 审查结论：非 DO 家庭里，孩子停用设备管理器 / 卸载 App 只是几步系统操作，
                // 终端无力阻止（能力边界见 PadGuardDeviceAdminReceiver 类注释）。
                // 若不提前讲明，家长会把「基础设备管理员」当成「设备所有者」来承诺，
                // 信任崩塌来得更猛。这里按真实 ControlMode 讲透边界：
                // 非 DO 是「尽力而为」（检测 + 上报，不阻止），DO/PO 才是系统级完整管控。
                // 与 D1「绑定向导补一页 DO 版/普通版能力分层说明」的决策对齐。
                if (kind == "device_admin") {
                    CapabilityTierCard(mode = mode)
                    DeviceAdminActions(context = context, mode = mode, vm = vm)
                }

                if (kind == "usage_stats") {
                    Button(
                        onClick = {
                            if (!vm.ensureGuardUnlocked()) return@Button
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

                // D5（L-CH-08）：电池优化白名单引导。
                // 受 Android / Play 家庭政策限制，本应用**不申请** REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                // （声明即被拒），因此无法直接以代码把自身加入豁免名单；
                // 只能引导用户进入系统「不受电池优化限制的应用」列表页，由用户手动把本应用设为
                // 「不受限制」。息屏后 Doze 不再冻结本应用的后台任务（MQTT 心跳、定位、消息推送），
                // 否则每晚息屏即脱管，"锁屏没反应"成首批投诉主源。
                if (kind == "battery_optimization") {
                    Button(
                        onClick = {
                            if (!vm.ensureGuardUnlocked()) return@Button
                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }
                                .onFailure { e ->
                                    Toast.makeText(context, "无法打开电池优化设置：${e.message}", Toast.LENGTH_LONG).show()
                                }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "前往设置（电池优化白名单）")
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
    vm: PermissionGuideViewModel
) {
    when (mode) {
        ControlMode.LEGACY -> {
            Button(
                onClick = {
                    if (!vm.ensureGuardUnlocked()) return@Button
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
                    if (!vm.ensureGuardUnlocked()) return@OutlinedButton
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

/**
 * D7（决策 D4 联动）：管控能力分层明示卡片。
 *
 * 文案边界与代码事实严格对齐（见 [com.padguard.child.receiver.PadGuardDeviceAdminReceiver]
 * 类注释与 [com.padguard.core.engine.guard.TamperDetector] 的设计立场）：
 * - DO/PO：系统级强制，系统设置中没有停用入口，终端无法被撤销；
 * - DEVICE_ADMIN：可锁屏/禁相机，但用户**能**停用，我们只有威慑文案 + 事后告警；
 * - LEGACY：纯用户态，防绕过能力最弱。
 *
 * 措辞用「尽力而为」而不是「防止」—— 检测的价值是不被蒙在鼓里，对外承诺必须诚实。
 */
@Composable
private fun CapabilityTierCard(mode: ControlMode) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "能力分层说明",
                style = MaterialTheme.typography.titleSmall
            )
            when (mode) {
                ControlMode.DEVICE_OWNER, ControlMode.PROFILE_OWNER -> {
                    Text(
                        text = "当前为「设备所有者 / 工作资料所有者」：防卸载、禁止恢复出厂等限制由系统级强制执行，系统设置中没有停用入口，孩子无法在终端上撤销。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                ControlMode.DEVICE_ADMIN -> {
                    Text(
                        text = "当前为「基础设备管理员」：锁屏、禁用相机可用，卸载前系统也会要求先停用设备管理器；但孩子仍可在设置中停用设备管理器，再卸载本 App —— 停用后绝大多数策略将静默失效。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "终端会检测权限降级并立刻上报家长，但检测发生在事实之后，属于「尽力而为」，无法从系统层面阻止停用。如需系统级防卸载，请升级为「设备所有者」（见下方 adb 命令）。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                else -> {
                    Text(
                        text = "当前为「普通模式」：防卸载、防绕过均为「尽力而为」—— 孩子可在设置中关闭无障碍服务、撤销权限或直接卸载本 App，终端无法从系统层面阻止。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "上述行为会被终端检测并上报家长，但检测发生在事实之后。建议先激活「设备管理员」，条件允许时再升级为「设备所有者」，以获得系统级防护。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
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
    "battery_optimization" -> "电池优化白名单"
    else -> "权限引导"
}

private fun descriptionFor(kind: String, mode: ControlMode): String = when (kind) {
    "device_admin" -> when (mode) {
        ControlMode.LEGACY -> "设备管理员是系统级权限，授予后本 App 才能锁定屏幕、禁用相机、防卸载等。"
        ControlMode.DEVICE_ADMIN -> "你已激活基础设备管理员。锁屏、禁用相机已可用；更高权限（Kiosk、USB 限制）需升级为设备所有者。"
        else -> "你已激活设备所有者，全部管控能力可用。"
    }
    "usage_stats" -> "使用情况访问是 Android 提供的统计权限，授予后我们才能读取每个 App 的真实使用时长。"
    "battery_optimization" -> "为确保息屏后仍能正常管控（锁屏、定位、消息推送），请将本应用加入系统「电池优化」豁免名单。受 Android 政策限制，需在此手动设置。"
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
    "battery_optimization" -> listOf(
        "点击下方「前往设置」",
        "在列表中找到「护航成长」",
        "选择「不优化 / 不受限制」",
        "返回本页面即可"
    )
    else -> emptyList()
}
