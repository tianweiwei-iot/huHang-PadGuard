package com.padguard.child.consent

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.padguard.child.capture.CapturePermissionActivity

/**
 * 一次性授权页 —— 孩子端装机路径上**唯一**需要家长做决定的界面。
 *
 * ## 家长看到的是什么
 * 一整张清单 + 一个按钮。点一次「同意并授权」之后：
 * - 对 App 自己能办到的（设备所有者机型下的全部运行时权限、安全加固），页面显示"在配置中"，不打扰；
 * - 对 Android 强制要求人工确认的（系统权限弹窗、设备管理员确认页、设置页开关、录屏确认），
 *   会自动跳过去，**开完返回立刻接下一项**，家长不需要回来点"继续"；
 * - 全部就绪自动进入下一步（扫码绑定家长端），此后正常使用期间不再出现第二个授权界面。
 *
 * ## 为什么清单是全量而不是只列关键项
 * 只列一部分，剩下"用到时再申请"正是过去的做法，结果是家长配置水印、
 * 远程拍照、实时看屏时被二次叫去系统设置。列全量才能兑现"一次到位"。
 */
@Composable
fun OneTimeConsentScreen(
    viewModel: OneTimeConsentViewModel = hiltViewModel()
) {
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val currentKey by viewModel.currentKey.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> viewModel.onRuntimePermissionsResult(result) }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.onReturnedToApp() }

    val adminLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.onReturnedToApp() }

    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.onReturnedToApp() }

    // ViewModel 不持有 Activity 引用，跳转统一由事件驱动；只有一处分发，不会漏类型。
    LaunchedEffect(Unit) {
        viewModel.launch.collect { event ->
            when (event) {
                is ConsentLaunch.Runtime -> runtimeLauncher.launch(event.permissions.toTypedArray())
                is ConsentLaunch.Settings -> settingsLauncher.launch(event.intent)
                is ConsentLaunch.DeviceAdmin -> adminLauncher.launch(event.intent)
                ConsentLaunch.MediaProjection ->
                    captureLauncher.launch(CapturePermissionActivity.intent(context))
            }
        }
    }

    // 兜底：个别 ROM 的系统设置页不会回传 result，只靠 ActivityResult 会永远停在等待态。
    ResumeEffect(onResume = viewModel::onReturnedToApp)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Text(
                text = "授权护航守护",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "下方是本应用正常提供服务所需的全部授权。点一次「同意并授权」，" +
                    "我们会自动带您完成系统要求的确认，全部就绪后即可扫码绑定家长端。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(20.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    items.forEach { item -> ConsentRow(item, currentKey) }
                }
            }

            if (message != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = message!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = viewModel::retryCurrent,
                enabled = phase != OneTimeConsentViewModel.Phase.RUNNING,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = primaryActionLabel(phase, items, currentKey),
                    style = MaterialTheme.typography.titleMedium
                )
            }

            if (phase != OneTimeConsentViewModel.Phase.IDLE) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = viewModel::finishAnyway,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("剩余项不再授权，直接进入")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "说明：使用情况访问、悬浮窗、无障碍服务与屏幕采集属于 Android 隐私保护项，" +
                    "任何应用都无法代替用户开启，必须由您或孩子在系统页面上确认。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ConsentRow(item: ConsentItem, currentKey: String? = null) {
    // 当前正在等家长去系统页开启的那一项。高亮出来，家长才不必在整列待办里猜"现在该开哪个"；
    // 自动接续生效时，这一行会随着流水线推进逐项前移，本身就是进度反馈。
    val waiting = item.state == ConsentItem.State.NEED_MANUAL && currentKey == item.key
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        val (tint, icon) = indicatorFor(item.state)
        when (item.state) {
            ConsentItem.State.RUNNING -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
            else -> Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (waiting) MaterialTheme.colorScheme.primary else tint,
                modifier = Modifier.size(20.dp)
            )
        }
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(text = item.title, style = MaterialTheme.typography.bodyLarge)
            if (item.impact.isNotBlank()) {
                Text(
                    text = item.impact,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            if (item.state == ConsentItem.State.NEED_MANUAL ||
                item.state == ConsentItem.State.SKIPPED
            ) {
                Text(
                    text = when {
                        item.state == ConsentItem.State.SKIPPED -> "已跳过"
                        waiting -> "正在等待您开启，开启后自动跳转下一项"
                        else -> "需您在系统页面中确认"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        item.state == ConsentItem.State.SKIPPED ->
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                        waiting -> MaterialTheme.colorScheme.primary
                        else -> Color(0xFFF9A825)
                    }
                )
            }
        }
    }
}

@Composable
private fun indicatorFor(state: ConsentItem.State): Pair<Color, androidx.compose.ui.graphics.vector.ImageVector> =
    when (state) {
        ConsentItem.State.DONE -> Color(0xFF2E7D32) to Icons.Filled.CheckCircle
        ConsentItem.State.RUNNING -> MaterialTheme.colorScheme.primary to Icons.Filled.RadioButtonUnchecked
        ConsentItem.State.NEED_MANUAL -> Color(0xFFF9A825) to Icons.Filled.RadioButtonUnchecked
        ConsentItem.State.SKIPPED ->
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f) to Icons.Filled.RadioButtonUnchecked
        ConsentItem.State.PENDING ->
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f) to Icons.Filled.RadioButtonUnchecked
    }

/** 底部主按钮文案：优先描述「当前正在等家长处理的那一步」。 */
private fun primaryActionLabel(
    phase: OneTimeConsentViewModel.Phase,
    items: List<ConsentItem>,
    currentKey: String?
): String = when (phase) {
    OneTimeConsentViewModel.Phase.IDLE -> "同意并授权"
    OneTimeConsentViewModel.Phase.RUNNING -> "正在自动配置…"
    OneTimeConsentViewModel.Phase.DONE -> "已完成"
    OneTimeConsentViewModel.Phase.ERROR -> "重试"
    OneTimeConsentViewModel.Phase.WAITING_PARENT -> {
        // 优先说清当前这一步，避免清单里有多项待办时按钮文案与实际跳转不符
        val item = items.firstOrNull { it.key == currentKey }
            ?: items.firstOrNull { it.state == ConsentItem.State.NEED_MANUAL }
        when (item?.source) {
            null -> "继续"
            ConsentItem.Source.DEVICE_ADMIN -> "去激活设备管理员"
            ConsentItem.Source.RUNTIME -> "去允许系统权限提示"
            ConsentItem.Source.MEDIA_PROJECTION -> "去开启屏幕采集"
            ConsentItem.Source.MANUAL_SETTING -> "去开启「" + item.title + "」"
        }
    }
}

/** 页面回到前台时回调：系统设置页的开关变更没有回调，只能靠重新可见来复检。 */
@Composable
private fun ResumeEffect(onResume: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onResume()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
