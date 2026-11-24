package com.padguard.child.permission

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.padguard.core.data.model.policy.ControlMode
import kotlinx.coroutines.launch

/**
 * 硬管控路径的「设备所有者(DO)预置」引导页。
 *
 * ## 只在家长选了硬管控时才出现
 * 装机首启二选一（[ManagementModeChoiceScreen]）选硬管控后才会挂载本页；
 * 选软管控的家长直接走三件套授权，不会被 DO 预置拦截。两档并行、互不打扰。
 *
 * ## 为什么 App 不能自己变成 DO
 * Android 不允许已安装应用自行提升为设备所有者，必须由外部预置完成
 * （`dpm set-device-owner` 或设置向导扫码）。本页只负责把合法路径清晰地给到家长，
 * 并支持「重新检测」后立即放行。
 *
 * ## 降级通道
 * 家长可在本页明确选择「改用软管控」：落 management_mode=SOFT（并置位旧 ack 标记兼容既有流程），
 * 立即放行；已授予的三件套权限与已下发的策略配置一律保留，日后可切回硬管控。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoProvisioningScreen() {
    val context = LocalContext.current
    val vm: DoProvisioningViewModel = hiltViewModel()
    val mode by vm.controlMode.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(text = "设备所有者预置（硬管控）") })
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
                    text = "防卸载、禁止恢复出厂、静默安装等核心管控能力，只有在设备成为「设备所有者」后才会由系统强制执行。请先按下方步骤完成预置，再继续装机。",
                    style = MaterialTheme.typography.bodyLarge
                )

                if (mode == ControlMode.DEVICE_OWNER) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "已检测到设备所有者，正在进入下一步…",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // 前置条件（最常见的失败原因，报错信息不指向真因，必须提前讲清）
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = "前置条件（务必先满足）", style = MaterialTheme.typography.titleSmall)
                        vm.provisioning.adbPreconditions.lines().forEach { line ->
                            if (line.isNotBlank()) {
                                Text(text = line, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                // adb 激活命令（一键复制）
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = "方式一：电脑执行以下命令（推荐调试 / 部署）", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = vm.provisioning.adbCommand,
                            modifier = Modifier.fillMaxWidth(),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(
                            onClick = { copyText(context, vm.provisioning.adbCommand, "激活命令已复制") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = "复制 adb 命令")
                        }
                    }
                }

                // 扫码预置（量产 / 首次开机）。受 Android 政策，App 内无法直接渲染二维码位图，
                // 这里给出 JSON 与生成指引，由部署方用系统相机扫码（与 ProvisioningGuide 口径一致）。
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = "方式二：设置向导扫码预置（量产 / 首次开机）", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "将本机 APK 放到可访问的 HTTPS 地址后，用下方 JSON 生成 DPC 二维码，在平板首次开机的设置向导阶段扫码即完成预置：",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = vm.provisioning.dpcQrJson(),
                            modifier = Modifier.fillMaxWidth(),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "提示：扫码部署需为 APK 提供下载地址与 SHA-256 校验值（见 ProvisioningGuide）。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // 常见失败原因对照，直接给出「下一步做什么」
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = "命令失败对照", style = MaterialTheme.typography.titleSmall)
                        vm.provisioning.failureHints.forEach { (reason, fix) ->
                            Text(
                                text = "• $reason → $fix",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                Button(
                    onClick = { vm.refresh() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "我已执行，重新检测")
                }

                // 降级到软管控：明确切档，不是"带风险继续"；两档配置各自保留，可随时切回。
                TextButton(
                    onClick = {
                        scope.launch { vm.fallbackToSoft() }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "改用软管控（放弃设备所有者能力，已开启的管控配置保留）",
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
