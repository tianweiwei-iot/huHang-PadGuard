package com.padguard.child.permission

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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.padguard.core.data.model.policy.ManagementMode
import kotlinx.coroutines.launch

/**
 * 装机首启的管控模式二选一（"安装应用之前给用户提示"的落点）。
 *
 * - 出现在登录 / 授权 / 绑定之前的**第一道**界面，家长必须先明确选一档；
 * - 选硬管控 → 入口分流进入 DO 预置引导（App 无法自行提权，须 adb / 扫码）；
 * - 选软管控 → 跳过 DO 预置，直接走三件套一次性授权；
 * - 两档授权与配置各自保留，后续在「我的」页可随时互换，互不清除。
 *
 * 文案只用主题容器色，跟随深浅主题，不硬编码浅底深字。
 */
@Composable
fun ManagementModeChoiceScreen() {
    val vm: ManagementModeChoiceViewModel = hiltViewModel()
    val scope = rememberCoroutineScope()

    Surface(
        modifier = Modifier.fillMaxSize(),
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
                text = "选择管控模式",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "安装已完成，首次使用请先确定这台平板的管控方式。两种模式都能随时在「我的」页切换，" +
                    "各自的授权与配置分别保留，互不影响。",
                style = MaterialTheme.typography.bodyMedium
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "硬管控（设备所有者 · DO）",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "系统级强制：防卸载、防强制停止、防改系统时间、真 Kiosk 锁死 HOME、静默安装应用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "前提：需通过电脑 adb 命令或设置向导扫码完成设备所有者预置，设备须为干净未登录状态（账号 0）。" +
                            "选定后按引导操作，完成前不会跳过。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Button(
                        onClick = { scope.launch { vm.choose(ManagementMode.HARD) } },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "选择硬管控")
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "软管控（无障碍 + 悬浮窗 + 设备管理器）",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "时间管理、应用管控、看屏与加锁均正常；无需电脑，按屏幕引导开启权限即可。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = "边界：孩子进入系统设置后仍可强制停止、卸载本应用或更改系统时间；安全模式与恢复出厂守不住。" +
                            "后续随时可切换为硬管控。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Button(
                        onClick = { scope.launch { vm.choose(ManagementMode.SOFT) } },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "选择软管控")
                    }
                }
            }
        }
    }
}
