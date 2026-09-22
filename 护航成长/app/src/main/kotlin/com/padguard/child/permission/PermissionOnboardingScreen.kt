package com.padguard.child.permission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.padguard.child.permission.PermissionGranter.GrantStep

/**
 * 权限一键获取页（初次打开 App 时展示）。
 *
 * - DO/PO 设备：[start] 直接在后台静默授予全部权限，本页**只显示进度**（进度条 + 步骤列表），无系统弹窗、无设置跳转；
 * - 非 DO 设备：进入"成为设备所有者"预置引导（拿到最高权限的唯一前置），完成后点"重新检测"自动转入后台进度；
 * - 完成后 [MainActivity.EntryRouter] 依据 [AuthRepository.isPermissionsDone] 自动推进到登录态。
 */
@Composable
fun PermissionOnboardingScreen(viewModel: PermissionOnboardingViewModel = hiltViewModel()) {
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val steps by viewModel.steps.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (phase) {
            PermissionOnboardingViewModel.Phase.IDLE ->
                IdleView(onStart = viewModel::start)

            PermissionOnboardingViewModel.Phase.RUNNING,
            PermissionOnboardingViewModel.Phase.DONE ->
                ProgressView(steps = steps, done = phase == PermissionOnboardingViewModel.Phase.DONE)

            PermissionOnboardingViewModel.Phase.NEED_PROVISIONING ->
                ProvisioningView(
                    guide = viewModel.provisioning,
                    onRetry = viewModel::retryProvisioning,
                    onActivateAdmin = { context.startActivity(viewModel.deviceAdminIntent()) },
                    onForceContinue = viewModel::forceContinue
                )

            PermissionOnboardingViewModel.Phase.ERROR ->
                ErrorView(error = error, onRetry = viewModel::start)
        }
    }
}

@Composable
private fun IdleView(onStart: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("获取设备管理权限", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "点击后将一键获取平板最高管理权限，并在后台完成全部管控所需权限的授予，过程无需手动操作。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp)) {
            Text("一键获取最高权限", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun ProgressView(steps: List<GrantStep>, done: Boolean) {
    val total = steps.size.coerceAtLeast(1)
    val completed = steps.count { it.state == GrantStep.State.DONE || it.state == GrantStep.State.UNSUPPORTED || it.state == GrantStep.State.FAILED }
    val progress = completed.toFloat() / total

    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            if (done) "权限获取完成" else "正在后台获取权限…",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            strokeCap = androidx.compose.material3.ProgressIndicatorDefaults.LinearStrokeCap
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "$completed / $total",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(20.dp))

        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            steps.forEach { StepRow(it) }
        }

        if (done) {
            Spacer(Modifier.height(16.dp))
            Text("正在进入主界面…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun StepRow(step: GrantStep) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val (tint, icon) = when (step.state) {
            GrantStep.State.DONE -> Color(0xFF2E7D32) to Icons.Filled.CheckCircle
            GrantStep.State.FAILED -> MaterialTheme.colorScheme.error to Icons.Filled.Error
            GrantStep.State.UNSUPPORTED -> Color(0xFFF9A825) to Icons.Filled.Error
            GrantStep.State.RUNNING -> MaterialTheme.colorScheme.primary to Icons.Filled.RadioButtonUnchecked
            GrantStep.State.PENDING -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f) to Icons.Filled.RadioButtonUnchecked
        }
        if (step.state == GrantStep.State.RUNNING) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(step.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (step.state == GrantStep.State.UNSUPPORTED) {
                Text("当前权限模式不支持，需设备所有者", style = MaterialTheme.typography.bodySmall, color = Color(0xFFF9A825))
            } else if (step.state == GrantStep.State.FAILED) {
                Text("授予失败，已记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ProvisioningView(
    guide: ProvisioningGuide,
    onRetry: () -> Unit,
    onActivateAdmin: () -> Unit,
    onForceContinue: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(24.dp).verticalScroll(rememberScrollState())
    ) {
        Text("需要设备所有者权限", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "后台静默获取全部权限需要「设备所有者（Device Owner）」这一最高权限。普通已安装应用无法自行升级为此身份，需通过下面任一方式完成预置。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(20.dp))

        Text("方式一（推荐，无需恢复出厂）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ) {
            Text(
                guide.adbCommand,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier.padding(12.dp)
            )
        }
        Spacer(Modifier.height(16.dp))

        Text("方式二（首次开机扫码）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ) {
            Text(
                guide.dpcQrJson,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier.padding(12.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "将上方 JSON 生成二维码，在平板首次开机的设置向导「扫码配置」步骤扫描即可。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        )

        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp)) {
            Text("我已激活设备所有者，重新检测")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onActivateAdmin, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text("仅激活设备管理员（能力受限）")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onForceContinue, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text("仍以当前权限继续（能力受限）")
        }
    }
}

@Composable
private fun ErrorView(error: String?, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text("权限获取失败", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(error ?: "未知错误", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp)) {
            Text("重试")
        }
    }
}
