package com.padguard.child.consent

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
 * 一张清单 + 一个按钮。点一次「同意并开始授权」之后：
 * - 对 App 自己能办的（DO 机型下的全部运行时权限、安全加固），页面显示"正在配置"，不打扰；
 * - 对 Android 强制要求人工确认的（系统权限弹窗、设备管理员确认页、设置页开关、录屏确认），
 *   会自动跳过去，**拨完开关自动回到本页并直接接下一项**，家长不需要按返回、也不需要回来点"继续"；
 * - 全部就绪自动进入下一步（扫码绑定家长端），此后正常使用期间不再出现第二个授权界面。
 *
 * ## 三条体验原则（本页所有文案与交互都服从它们）
 * 1. **每一行都能点**：家长想先办哪一项就点哪一项，而不是只能靠底部按钮猜"下一步是哪个"。
 *    行不可点的直接后果就是"这项授权选不中 / 好像不能授权"。
 * 2. **状态只有三种人话**：已完成 / 待你确认 / 可选未开启。不出现"待处理"这种
 *    让家长分不清是"我还没做"还是"系统不让我做"的中间态。
 * 3. **底部只有一条主路径 + 一条明说代价的退路**：
 *    主按钮永远等于"下一步该做什么"；退路只在真的还有未完成项时出现，
 *    并且点下去先弹出确认，写清"跳过会失去哪些能力"，而不是一个含义模糊的
 *    「剩余项不再授权，直接进入」。
 *
 * ## [autoStart]：补授权场景
 * 默认为 false（首次装机，需家长主动点「同意并开始授权」）。
 * 当入口分流因「设备管理员未激活」把已装机用户带回本页时（[com.padguard.child.MainActivity]），
 * 置 true：家长此前已同意过协议，无需再点按钮，自动进入流水线并直接拉起 DA 激活页。
 */
@Composable
fun OneTimeConsentScreen(
    autoStart: Boolean = false,
    viewModel: OneTimeConsentViewModel = hiltViewModel()
) {
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val currentKey by viewModel.currentKey.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val guide by viewModel.guide.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 跳过确认弹窗：默认收起，避免误触直接退出授权流程
    var showSkipDialog by remember { mutableStateOf(false) }

    // 退出授权流程确认弹窗：默认收起。授权进行中按系统返回键不再直接关掉整个 App
    // （"返回键把App整关了"的根因就是缺这一步拦截），而是先确认；进度已落盘，确认退出后重进从断点续跑。
    var showExitConfirm by remember { mutableStateOf(false) }

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

    // VPN 授权：Intent 由 VpnService.prepare() 生成，结果不看 resultCode（部分 ROM 已授权也回 CANCELED），
    // 一律由 ViewModel 以 isPrepared() 复核真实状态。
    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.onVpnConsentResult() }

    // ViewModel 不持有 Activity 引用，跳转统一由事件驱动；只有一处分发，不会漏类型。
    //
    // ⚠️ 每次 launch 都必须接住异常：系统页在本机不存在时 launch() 抛
    // ActivityNotFoundException，会把整页打崩（家长看到的是"点一下，界面闪没了"）。
    // 接住后交给 ViewModel 记录并给出可读提示，清单继续可用。
    LaunchedEffect(Unit) {
        viewModel.launch.collect { event ->
            val key = when (event) {
                is ConsentLaunch.Runtime -> OneTimeConsentViewModel.KEY_RUNTIME
                is ConsentLaunch.Settings -> event.key
                is ConsentLaunch.DeviceAdmin -> OneTimeConsentViewModel.KEY_DEVICE_ADMIN
                is ConsentLaunch.VpnPrepare -> OneTimeConsentViewModel.KEY_VPN
                ConsentLaunch.MediaProjection -> OneTimeConsentViewModel.KEY_CAPTURE
            }
            runCatching {
                when (event) {
                    is ConsentLaunch.Runtime -> runtimeLauncher.launch(event.permissions.toTypedArray())
                    is ConsentLaunch.Settings -> settingsLauncher.launch(event.intent)
                    is ConsentLaunch.DeviceAdmin -> adminLauncher.launch(event.intent)
                    is ConsentLaunch.VpnPrepare -> vpnLauncher.launch(event.intent)
                    ConsentLaunch.MediaProjection ->
                        captureLauncher.launch(CapturePermissionActivity.intent(context))
                }
            }.onFailure { viewModel.onLaunchFailed(key, it.message ?: "系统页面不可用") }
        }
    }

    // 补授权场景（autoStart=true）：家长此前已同意过协议、仅设备管理员缺失，
    // 自动进入流水线（跳过「同意并授权」按钮）并直接拉起 DA 激活页。
    // 前台服务把授权页顶回前台（实例重建）时也借此续跑。
    LaunchedEffect(autoStart) {
        if (autoStart && !viewModel.hasStarted) viewModel.retryCurrent()
    }

    // 进程被系统回收 / 上次未完成退出后重进：进度已落盘，进入即自动从断点续跑，
    // 不再让家长从头点「同意并开始授权」（这正是之前"设置页待会儿回来重置"的根因之一）。
    // 仅 autoStart=false 时接管，避免与上方补授权场景重复触发。
    LaunchedEffect(Unit) {
        if (!autoStart && viewModel.hasStarted && phase == OneTimeConsentViewModel.Phase.IDLE) {
            viewModel.retryCurrent()
        }
    }

    // 兜底：个别 ROM 的系统设置页不会回传 result，只靠 ActivityResult 会永远停在等待态。
    ResumeEffect(onResume = viewModel::onReturnedToApp)

    // 拦截系统返回键：授权流程未走完时，按返回不直接 finish 整个 App（此前"返回键把App整关了"
    // 正是这个缺失导致的）。进度已落盘，这里弹确认；完成态（DONE）放行系统默认行为。
    BackHandler(enabled = phase != OneTimeConsentViewModel.Phase.DONE) {
        showExitConfirm = true
    }

    val doneCount = items.count { it.state == ConsentItem.State.DONE }
    val waitingCount = items.count { it.state == ConsentItem.State.NEED_MANUAL }
    val pendingCount = items.count { it.state == ConsentItem.State.PENDING }
    val remainingCount = waitingCount + pendingCount
    // 只认"当前正在等的那一项"来生成按钮文案：拿清单里第一个待确认项去命名会导致
    // "按钮写着悬浮窗、实际点开的是使用情况访问"这种错位，比不写名字更糟。
    val waitingItem = items.firstOrNull {
        it.key == currentKey && it.state == ConsentItem.State.NEED_MANUAL
    }

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
                text = "下方是本应用正常工作所需的全部授权。点一次「同意并开始授权」，" +
                    "系统要求的确认我们会逐项带你过去；每开完一项都会自动回到这里并接着下一项。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(16.dp))

            ProgressHeader(
                done = doneCount,
                total = items.size,
                waiting = waitingCount,
                phase = phase
            )

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    items.forEach { item ->
                        ConsentRow(
                            item = item,
                            isCurrent = currentKey == item.key,
                            clickable = phase != OneTimeConsentViewModel.Phase.RUNNING &&
                                item.state != ConsentItem.State.DONE,
                            onClick = { viewModel.openItem(item.key) }
                        )
                    }
                }
            }

            // 跳转系统设置页前的分步指引：无障碍 / 电池优化这类"列表型"页面不直达本应用
            // 开关，家长进了设置页不知道去哪找——真机反馈"逻辑不清"的直接来源。
            // 用中性信息色（secondaryContainer）而不是错误色：它是"教你怎么做"，不是报错。
            if (guide != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Text(
                        text = guide!!,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            // 中性告知单独一层：跳过某项、复检进行中/未检测到变化都属于正常路径，
            // 用信息色而不是错误色。早期实现把它们和真错误一起塞进 message 走红底，
            // 家长反馈"授权过程一直在报错"即由此而来。
            if (notice != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Text(
                        text = notice!!,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            if (message != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = message!!,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = viewModel::retryCurrent,
                enabled = phase != OneTimeConsentViewModel.Phase.RUNNING &&
                    phase != OneTimeConsentViewModel.Phase.DONE,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = primaryActionLabel(phase, waitingItem, remainingCount, viewModel.hasStarted),
                    style = MaterialTheme.typography.titleMedium
                )
            }

            // 退路：只在真的还有未完成项、且流程已经开跑时出现。
            // 文案直接说"跳过什么"，并先弹确认——不再用「剩余项不再授权，直接进入」这种
            // 让人看不懂、也不知道点下去会发生什么的措辞。
            if (remainingCount > 0 && phase != OneTimeConsentViewModel.Phase.IDLE &&
                phase != OneTimeConsentViewModel.Phase.RUNNING &&
                phase != OneTimeConsentViewModel.Phase.DONE
            ) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { showSkipDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("暂时跳过剩余 $remainingCount 项，先进入使用")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "说明：能自动检测的项（使用情况访问、悬浮窗、无障碍、电池优化、精确闹钟、通知等）" +
                    "开完即自动回到本页并跳到下一项，无需按返回、也无需再点继续。" +
                    "厂商「自启动 / 后台弹出界面」没有公开状态接口，带您到设置页后按返回即自动接续，" +
                    "若按了 HOME，通知栏会留一条可点的「回来继续」。" +
                    "以上开关与屏幕采集均属 Android 隐私保护项，任何应用（包括设备所有者）" +
                    "都无法代替用户开启，必须由您在系统页面上确认。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showSkipDialog) {
        SkipRemainingDialog(
            remaining = items.filter {
                it.state == ConsentItem.State.NEED_MANUAL || it.state == ConsentItem.State.PENDING
            },
            onDismiss = { showSkipDialog = false },
            onConfirm = {
                showSkipDialog = false
                viewModel.finishAnyway()
            }
        )
    }

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("退出授权流程？") },
            text = { Text("授权进度已自动保存，下次进入会从当前进度继续，不会从头再来。确定退出吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirm = false
                    (context as? android.app.Activity)?.finish()
                }) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) { Text("继续授权") }
            }
        )
    }
}

/**
 * 顶部进度摘要。
 *
 * 为什么要有：清单是 9 行的长列表，家长最需要回答的问题是"还剩多少、我走到哪了"。
 * 只给一列灰点，他得自己数；数不清就会怀疑是不是卡住了。
 */
@Composable
private fun ProgressHeader(
    done: Int,
    total: Int,
    waiting: Int,
    phase: OneTimeConsentViewModel.Phase
) {
    val progress = if (total > 0) done.toFloat() / total.toFloat() else 0f
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when (phase) {
                        // 不写"全部授权已开启"：厂商私有开关没有查询接口，清单里如实保留
                        // 「已带您前往设置（请确认开启）」行，不能把流程走完说成开关全开。
                        OneTimeConsentViewModel.Phase.DONE -> "授权流程已完成"
                        OneTimeConsentViewModel.Phase.RUNNING -> "正在自动配置…"
                        else -> "已完成 $done / $total 项"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (waiting > 0) {
                    Text(
                        text = "待你确认 $waiting 项",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
            )
        }
    }
}

@Composable
private fun ConsentRow(
    item: ConsentItem,
    isCurrent: Boolean,
    clickable: Boolean,
    onClick: () -> Unit
) {
    // 当前正在等家长去系统页开启的那一项。高亮出来，家长才不必在整列待办里猜"现在该开哪个"；
    // 自动接续生效时，这一行会随着流水线推进逐项前移，本身就是进度反馈。
    val waiting = isCurrent && item.state != ConsentItem.State.DONE
    val stateText = statusText(item, isCurrent)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = item.title, style = MaterialTheme.typography.bodyLarge)
                if (item.optional) {
                    Text(
                        text = "可选",
                        modifier = Modifier.padding(start = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    )
                }
            }
            if (item.impact.isNotBlank()) {
                Text(
                    text = item.impact,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            if (stateText != null) {
                Text(
                    text = stateText,
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        waiting -> MaterialTheme.colorScheme.primary
                        item.state == ConsentItem.State.SKIPPED ->
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                        // 走 tertiary 而不是写死 amber800：amber800 在深色主题上对比度不足，
                        // 且主题换肤后完全跟不上
                        else -> MaterialTheme.colorScheme.tertiary
                    }
                )
            }
        }
        // 可点击的行给一个明确的动作提示：让"能点"这件事本身被看见
        if (clickable) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (item.state == ConsentItem.State.SKIPPED) "补开" else "去开启",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/** 行状态文案：只说人话，并且说清"下一步会发生什么"。 */
private fun statusText(item: ConsentItem, isCurrent: Boolean): String? = when (item.state) {
    ConsentItem.State.DONE -> null
    ConsentItem.State.RUNNING -> "正在自动配置…"
    ConsentItem.State.NEED_MANUAL ->
        if (isCurrent) "正在等待你开启，开完自动跳到下一项" else "需你在系统页面中确认"
    // 平台不提供查询接口：如实说明"带去过了，开关是否打开由您确认"，
    // 但明确告诉家长流程已自动接续，不必回来再点一次。
    ConsentItem.State.CONFIRMED -> "已带您前往设置（请确认开启），流程已自动继续"
    ConsentItem.State.SKIPPED -> if (item.optional) "未开启（可稍后补开）" else "已跳过"
    ConsentItem.State.PENDING -> if (item.optional) "未开启（可选）" else "排队等待处理"
}

@Composable
private fun indicatorFor(state: ConsentItem.State): Pair<Color, androidx.compose.ui.graphics.vector.ImageVector> =
    when (state) {
        // DONE 用 primary 而不是写死 green800：后者在深色主题上几乎与背景同深浅
        ConsentItem.State.DONE -> MaterialTheme.colorScheme.primary to Icons.Filled.CheckCircle
        ConsentItem.State.RUNNING -> MaterialTheme.colorScheme.primary to Icons.Filled.RadioButtonUnchecked
        ConsentItem.State.NEED_MANUAL -> MaterialTheme.colorScheme.tertiary to Icons.Filled.RadioButtonUnchecked
        // 半确认：用半透明的主色 + 空心圈，既不冒充"已完成"，也不像"还没办"
        ConsentItem.State.CONFIRMED ->
            MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) to Icons.Filled.RadioButtonUnchecked
        ConsentItem.State.SKIPPED ->
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f) to Icons.Filled.RadioButtonUnchecked
        ConsentItem.State.PENDING ->
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f) to Icons.Filled.RadioButtonUnchecked
    }

/**
 * 底部主按钮文案：永远回答"我现在该做什么"。
 *
 * 刻意不再出现「已完成」这种"陈述句当按钮"的写法 —— 一个写着「已完成」却还能点的按钮，
 * 家长不知道点下去会发生什么，而这正是用户反馈"看不懂最后一步"的直接原因。
 */
private fun primaryActionLabel(
    phase: OneTimeConsentViewModel.Phase,
    waitingItem: ConsentItem?,
    remaining: Int,
    resumed: Boolean = false
): String = when (phase) {
    OneTimeConsentViewModel.Phase.IDLE -> if (resumed) "继续授权（从断点）" else "同意并开始授权"
    OneTimeConsentViewModel.Phase.RUNNING -> "正在自动配置…"
    OneTimeConsentViewModel.Phase.DONE -> "全部完成，正在进入下一步…"
    OneTimeConsentViewModel.Phase.ERROR -> "重试"
    OneTimeConsentViewModel.Phase.WAITING_PARENT -> when {
        waitingItem != null -> "去开启「${waitingItem.title}」"
        remaining > 0 -> "继续下一项（还剩 $remaining 项）"
        else -> "继续"
    }
}

/**
 * 「暂时跳过」的确认弹窗。
 *
 * 必须说清代价：跳过设备管理员 → 少了防卸载/禁相机；跳使用情况访问 → 时长统计与限额
 * 会静默失效。家长只有知道失去什么，才不会在事后抱怨"功能没用"。
 */
@Composable
private fun SkipRemainingDialog(
    remaining: List<ConsentItem>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("暂时跳过这 ${remaining.size} 项？") },
        text = {
            Column {
                Text(
                    text = "可以稍后在「我的 → 权限引导」里补开。跳过期间，下面这些能力不会生效：",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                remaining.forEach { item ->
                    Text(
                        text = "· ${item.title}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (item.impact.isNotBlank()) {
                        Text(
                            text = "  ${item.impact}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("仍然跳过") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("返回继续授权") }
        }
    )
}

/**
 * 页面回到前台时回调：系统设置页的开关变更没有回调，只能靠重新可见来复检。
 *
 * ⚠️ 必须忽略 `addObserver` 的**状态补发**：`LifecycleRegistry.addObserver` 会把新观察者
 * 直接补齐到当前状态，于是注册瞬间就会收到一次 `ON_RESUME`。若不丢弃这一次，
 * 任何"Activity 重建（旋转 / 被系统回收后回来）"都会在家长还没做任何动作时触发一次
 * `advance()` —— 表现就是把当前这一步误判成"回来还没开"，直接跳过它、
 * 甚至当场弹出下一条权限框。只有在注册之后**真正发生**的 ON_RESUME 才算"从设置页回来"。
 */
@Composable
private fun ResumeEffect(onResume: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        var registered = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && registered) onResume()
        }
        lifecycle.addObserver(observer) // 这里可能同步补发 ON_RESUME，此时 registered 仍为 false
        registered = true
        onDispose { lifecycle.removeObserver(observer) }
    }
}
