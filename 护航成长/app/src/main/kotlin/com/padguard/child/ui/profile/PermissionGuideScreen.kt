package com.padguard.child.ui.profile

import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.padguard.child.capture.CapturePermissionActivity
import com.padguard.child.permission.PermissionGuideViewModel
import com.padguard.child.permission.RomSettings
import com.padguard.child.permission.SpecialPermission
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

    // 从系统设置页返回后重新核对授权状态（与装机授权流水线「拨完开关自动跳下一项」同源：
    // 系统设置页不回传任何回调，只能靠页面重新可见来复检）。
    // resumeNonce 每次 ON_RESUME 自增，作为下方状态查询的重组键，确保"回去拨完开关→回来即显示已授权"。
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumeNonce by remember { mutableStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeNonce++
        }
        lifecycle.addObserver(observer)
        // 离开授权引导页时收口操作闸门豁免窗口（打开设置页时曾为无障碍等设置页开过豁免），
        // 避免豁免残留。幂等，多次调用无害。
        onDispose {
            lifecycle.removeObserver(observer)
            vm.endSettingsSession()
        }
    }

    // 当前 per-kind 页对应的 SpecialPermission（用于"拨完开关回来自动跳下一项"判定）。
    // device_admin / web_guard 不是 SpecialPermission 枚举项，单独走控制模式 / VPN 通道，这里留 null。
    // 其余 detectable 项（悬浮窗 / 使用情况 / 无障碍 / 电池优化 / 精确闹钟 / 通知）全部覆盖，
    // 让每一种授权模式都和"允许在其它应用上层显示"一样：选完开关回来即自动接续下一项。
    val currentPerm: SpecialPermission? = permOfKind(kind)
    // 点「去开启」时记住本项的分步指引：无障碍 / 电池优化这类列表型设置页不会直达
    // 本应用的开关，家长要在几十项里自己找「护航成长」。与装机向导同一套指引，
    // 返回本页后卡片仍在，家长可对照着核对刚才拨的开关是否生效。
    var activeGuide by remember { mutableStateOf<String?>(null) }
    // 记录本项首帧授予状态，作为"本次返回是否由「未完成→完成」"的判定基线。
    val prevGranted = remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(resumeNonce) {
        if (resumeNonce == 0) {
            // 首帧只建立基线，不触发任何跳转
            prevGranted.value = currentPerm?.let { vm.isAuthorizationGranted(it) }
            return@LaunchedEffect
        }
        vm.refresh()
        val perm = currentPerm ?: return@LaunchedEffect
        val nowGranted = vm.isAuthorizationGranted(perm)
        val wasGranted = prevGranted.value
        prevGranted.value = nowGranted
        // 与「允许在其它应用上层显示」完全一致：本次从设置页返回后发现本项由「未完成」变「完成」，
        // 且仍有其它缺失项，则自动接续到下一授权项（复用装机自动接续流水线，无需手动点「继续」）。
        if (wasGranted == false && nowGranted && vm.missingAuthorizations().isNotEmpty()) {
            vm.startConsentReview()
        }
    }

    // VPN 授权确认框：Intent 由 VpnService.prepare() 生成，只能原样交给系统。
    // 结果一律以 isWebGuardPrepared() 复核（部分 ROM 已授权也回 CANCELED）。
    val vpnLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        val ok = vm.isWebGuardPrepared()
        Toast.makeText(
            context,
            if (ok) "已授权，网址拦截开始生效" else "未授权：家长端配置的网址拦截不会生效",
            Toast.LENGTH_LONG
        ).show()
        if (ok) vm.syncWebGuardNow()
    }

    // 看屏授权（屏幕采集）重授权：补授权入口点「去授权 / 重新授权」时拉起系统确认框，
    // 授权页据此只补令牌、不启动采集。返回后由本页 ON_RESUME 自动复检状态，无需处理 result。
    val captureLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { /* 返回即复检，无需处理 */ }
    val onCaptureAuthorize: () -> Unit = authLabel@{
        if (!vm.ensureGuardUnlocked()) return@authLabel
        val intent = CapturePermissionActivity.intent(context).apply {
            putExtra(CapturePermissionActivity.EXTRA_CAPTURE_INTENT, vm.captureConsentIntent())
        }
        runCatching { captureLauncher.launch(intent) }
            .onFailure { e ->
                Toast.makeText(context, "无法打开屏幕采集授权页：${e.message}", Toast.LENGTH_LONG).show()
            }
    }
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

                // 「授权总览」（kind=all）：把本机适用的**全部**需人工确认项摊开，
                // 不再只列原先 4 个 kind。悬浮窗、无障碍、安装未知应用、电池优化、精确闹钟、
                // 通知、厂商后台开关此前在 App 内根本没有补开入口，掉了只能重装。
                if (kind == "all") {
                    AuthorizationOverview(
                        vm = vm,
                        resumeNonce = resumeNonce,
                        onCaptureAuthorize = onCaptureAuthorize
                    )
                }

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

                // 能力分层明示。
                // 审查结论：非 DO 家庭里，孩子停用设备管理器 / 卸载 App 只是几步系统操作，
                // 终端无力阻止（能力边界见 PadGuardDeviceAdminReceiver 类注释）。
                // 若不提前讲明，家长会把「基础设备管理员」当成「设备所有者」来承诺，
                // 信任崩塌来得更猛。这里按真实 ControlMode 讲透边界：
                // 非 DO 是「尽力而为」（检测 + 上报，不阻止），DO/PO 才是系统级完整管控。
                // 绑定向导补充设备所有者(Device Owner)与普通模式的管控能力分层说明。
                if (kind == "device_admin") {
                    CapabilityTierCard(mode = mode)
                    DeviceAdminActions(context = context, mode = mode, vm = vm)
                }

                // 统一的「去开启」块：覆盖全部 detectable SpecialPermission（悬浮窗 / 使用情况 /
                // 无障碍 / 电池优化 / 精确闹钟 / 通知）。每项的"状态卡 + 去开启按钮"同构，
                // 且都复用装机流水线的逻辑——打开系统设置页（带操作闸门豁免）→ 返回 ON_RESUME
                // 复检 → 发现已授予即自动跳下一项，无需手动返回或再点「继续」。
                // 之前只有悬浮窗 / 使用情况 / 电池优化零星实现了"自动跳下一项"，无障碍等缺失该项体验，
                // 本次按同一模型补齐，做到"所有授权模式都像悬浮窗一样"。
                // 受 Android / Play 家庭政策限制，电池优化等项无法由代码静默授予，只能引导家长手动开；
                // 其余项同理（AppOps / 无障碍连 Device Owner 都无权代开），故统一走"引导 + 自动续接"。
                currentPerm?.let { perm ->
                    val granted = remember(resumeNonce) {
                        vm.isAuthorizationGranted(perm)
                    }
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = when {
                                granted -> "已开启：${perm.label}已授权，相关管控正常生效。"
                                // 电池优化：华为/荣耀的「受保护应用 / 后台运行」与官方
                                // isIgnoringBatteryOptimizations 是两套互不联通的机制，厂商页
                                // 加白后查询 API 仍恒 false。继续红字报「尚未开启」就是
                                // 装机向导刚修掉的"我明明开了它却说没开"。
                                perm == SpecialPermission.BATTERY_OPTIMIZATION ->
                                    "请在刚打开的电池页确认：本应用已设为「允许后台运行 / 不受限制」。" +
                                        "厂商省电白名单系统查询不到，本页显示未开启不代表没生效。"
                                else -> "尚未开启：${perm.impact}"
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = when {
                                granted -> MaterialTheme.colorScheme.primary
                                perm == SpecialPermission.BATTERY_OPTIMIZATION ->
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                                else -> MaterialTheme.colorScheme.error
                            }
                        )
                    }
                    Button(
                        onClick = {
                            if (!vm.ensureGuardUnlocked()) return@Button
                            // 先把路径讲清楚再放家长进系统页：设备切后台后本页重组仍保留这张卡，
                            // 回来还能照着核对。直达开关页的项返回 null，自然不显示。
                            activeGuide = RomSettings.settingsStepsHint(
                                RomSettings.vendorOfDevice(), perm.key
                            )
                            // 走统一 openSettingsFor：带操作闸门豁免（无障碍设置页会被闸门弹回，
                            // 其它项豁免无害），OEM ROM 优先厂商私有页、原生回落应用详情，四级降级链兜底。
                            val intent = vm.openSettingsFor(perm)
                            if (intent == null) {
                                Toast.makeText(
                                    context,
                                    "未找到「${perm.label}」的设置入口，请手动到系统设置开启",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                runCatching { context.startActivity(intent) }
                                    .onFailure { e ->
                                        Toast.makeText(
                                            context,
                                            "无法打开设置：${e.message}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "去开启（${perm.label}）")
                    }
                }
                activeGuide?.let { hint -> StepsHintCard(text = hint) }

                // 上网防护（本地 DNS 过滤 VPN）授权。
                // 这是「禁访问某网站 / 白名单上网 / 违规分类拦截」在设备侧唯一的执行通道：
                // Android 要求全设备流量通道必须由用户在系统确认框里点「允许」，
                // 而应用无法从后台弹出那个框。装机时没授权、家长后来又开启了过滤，
                // 就必须回到这里补一次，否则策略永远不生效。
                if (kind == "web_guard") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = if (vm.isWebGuardPrepared()) {
                                "已授权：网址拦截生效中。"
                            } else {
                                "尚未授权：家长端配置的「网址拦截」当前不会生效。"
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (vm.isWebGuardPrepared()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                    }
                    Button(
                        onClick = {
                            if (!vm.ensureGuardUnlocked()) return@Button
                            val intent = vm.prepareVpnIntent()
                            if (intent == null) {
                                // 已授权（可能是设备所有者已代授权）：直接按策略对账一次
                                vm.syncWebGuardNow()
                                Toast.makeText(context, "已授权，正在开启网址过滤", Toast.LENGTH_SHORT).show()
                            } else {
                                runCatching { vpnLauncher.launch(intent) }
                                    .onFailure { e ->
                                        Toast.makeText(context, "无法打开 VPN 授权页：${e.message}", Toast.LENGTH_LONG).show()
                                    }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = if (vm.isWebGuardPrepared()) "重新检查授权" else "去授权（上网防护）")
                    }
                }

                // 任何单项引导页都提供「一键补齐其余授权」：点一次进入与装机完全相同的
                // 自动接续流水线，开完一项自动跳下一项，不必逐个页面手动返回。
                if (kind != "all" && vm.missingAuthorizations().isNotEmpty()) {
                    OutlinedButton(
                        onClick = vm::startConsentReview,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("一键补齐其余 ${vm.missingAuthorizations().size} 项（自动逐项跳转）")
                    }
                }
            }
        }
    }
}

/**
 * 授权总览与补授权中心：本机适用的全部「需人工确认」项 + 看屏授权 + 一键补齐入口。
 *
 * 每一项都以「开关行」呈现：清晰显示已授权（开）/ 未授权（关），点击整行或开关即前往系统设置页
 * 开启或关闭（应用无法直接切换系统权限，统一由家长在系统页面操作）。
 * 对没有查询接口的厂商开关如实标注「需到设置确认」，不冒充已开启。
 *
 * 看屏授权（MediaProjection）单独置顶：它走系统投屏确认、是最易在重启后丢失的一项，
 * 也是历史上唯一会在孩子使用中弹确认框的项；现在由家长经本入口主动补授权，使用期不再打扰孩子。
 */
@Composable
private fun AuthorizationOverview(
    vm: PermissionGuideViewModel,
    resumeNonce: Int,
    onCaptureAuthorize: () -> Unit
) {
    // resumeNonce 每次从设置页返回自增：作为重组键强制本卡片重新查询各项授予状态，
    // 做到「回去拨完开关 → 回到本页立即显示已开启」，与装机流水线自动跳下一项同源。
    val applicable = remember(resumeNonce) { vm.applicableAuthorizations() }
    // missing 已是「待补齐」口径（只含可检测且确实未授予的项），不会再被
    // 无状态接口的厂商开关恒 false 卡成"永远还有一项没开"。
    val missing = remember(resumeNonce) { vm.missingAuthorizations() }
    // 厂商开关单独统计：它们仍需家长到设置页确认一次，但既不能谎报已开启，
    // 也不能算进待办数（否则「一键补齐」永不消失、从设置页返回反复重入流水线）。
    val noStateApi = remember(resumeNonce) { vm.noStateApiAuthorizations().size }
    // 系统运行时权限单列：可能含家长装机时主动拒绝的项，不算「待补齐」、不驱动自动接续，
    // 但也不静默吞掉（AuthorizationKeeper 已不再因这类项反复拉回授权页）。
    val missingRuntime = remember(resumeNonce) { vm.missingRuntimeAuthorizations() }

    // 看屏授权三态：令牌就位 = 已授权；曾同意但令牌失效（重启后）= 待重新授权；否则待授权。
    val captureHasToken = remember(resumeNonce) { vm.isCaptureAuthorized() }
    val captureEverGranted = remember(resumeNonce) { vm.captureEverGranted() }

    // 在组合作用域内取一次 Context，供下方 onOpen 普通 lambda 内复用（组合局部只能在 @Composable 内读取）。
    val ctx = LocalContext.current
    // 最近一次点开的行：把分步指引就地展开在它下面。家长不会读一段和当前项无关的通用说明，
    // 且从设置页返回后卡片还在，可对照核对刚才那一步。再次点同一行会收起。
    var activeKey by remember { mutableStateOf<String?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 「其中」而非「另有」：applicable.size 本就包含这 N 项厂商开关，
            // 写「另有」既重复计数，又会让家长误以为凭空多出几项没办。
            Text(
                text = "本机共 ${applicable.size} 项需在系统页面确认的授权" +
                    if (noStateApi > 0) {
                        "（其中 $noStateApi 项为厂商开关，系统不提供状态查询，可在下方按需查看）；" +
                            "实时看屏授权单独列于下方"
                    } else {
                        "；实时看屏授权单独列于下方"
                    },
                style = MaterialTheme.typography.titleSmall
            )

            // 看屏授权（屏幕采集）：置顶。最易在重启后丢失，也是唯一曾在使用中弹孩子窗的项。
            CaptureAuthRow(
                hasToken = captureHasToken,
                everGranted = captureEverGranted,
                onAuthorize = onCaptureAuthorize
            )

            HorizontalDivider()

            // 逐项「开关行」：已授权显示开、未授权显示关，点击前往系统设置开启 / 关闭。
            applicable.forEach { perm ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    PermissionToggleRow(
                        perm = perm,
                        granted = remember(resumeNonce) { vm.isAuthorizationGranted(perm) },
                        onOpen = toggleLabel@{
                            if (!vm.ensureGuardUnlocked()) return@toggleLabel
                            // 与装机向导同口径：进设置页前先把"去哪找本应用"讲清楚。
                            activeKey = if (activeKey == perm.key) null else perm.key
                            val intent = vm.openSettingsFor(perm)
                            if (intent == null) {
                                Toast.makeText(
                                    ctx,
                                    "未找到「${perm.label}」的设置入口，请手动到系统设置开启",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                runCatching { ctx.startActivity(intent) }
                                    .onFailure { e ->
                                        Toast.makeText(
                                            ctx,
                                            "无法打开设置：${e.message}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                            }
                        }
                    )
                    if (activeKey == perm.key) {
                        RomSettings.settingsStepsHint(RomSettings.vendorOfDevice(), perm.key)
                            ?.let { StepsHintCard(text = it) }
                    }
                }
            }

            HorizontalDivider()

            // 系统运行时权限与上面的「需人工确认」项不同口径：家长可能在装机系统弹窗里
            // 主动拒绝过某项，非 DO 设备又没有静默补授，它会一直缺失。这类项**不算脱管**
            // （见 AuthorizationKeeper.droppedRuntimePermissions），不会再反复把家长拉回
            // 授权页；但如实告知 + 给一个恒可打开的入口，避免修完骚扰反而变成静默吞掉。
            if (missingRuntime.isNotEmpty()) {
                Text(
                    text = "另有 ${missingRuntime.size} 项系统权限未授予：" +
                        "${missingRuntime.joinToString("、")}。" +
                        "若当初是您主动拒绝的，可以不必处理，也不会再反复提示；" +
                        "需要相关功能时点下方按钮到应用详情页开启。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
                OutlinedButton(
                    onClick = {
                        runCatching { ctx.startActivity(vm.openAppDetails()) }
                            .onFailure { e ->
                                Toast.makeText(
                                    ctx,
                                    "无法打开应用详情页：${e.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("前往应用详情页开启系统权限")
                }
            }

            if (missing.isNotEmpty()) {
                Button(
                    onClick = vm::startConsentReview,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("一键补齐剩余 ${missing.size} 项（自动逐项跳转）")
                }
            } else {
                // 可检测项已全部开启。若本机还有无状态接口的厂商开关，不能谎称"全部已开启"，
                // 但也不再把它们算成待办（否则按钮永远消不掉）。
                Text(
                    text = if (noStateApi > 0) {
                        "可检测项已全部开启。另有 $noStateApi 项厂商开关系统不提供状态查询，" +
                            "如需确认请点上方对应行前往设置页查看；已开启的无需重复操作。"
                    } else {
                        "全部已开启。若某项被系统关闭，本页会自动提示并带您补开。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * 系统设置页的分步操作指引卡，与装机向导 [com.padguard.child.consent.OneTimeConsentScreen]
 * 同款中性信息色：它是"教你怎么找"，不是报错，绝不能用错误色吓家长。
 *
 * 内容由 [RomSettings.settingsStepsHint] 纯函数给出；直达开关页的项返回 null，调用方不显示。
 */
@Composable
private fun StepsHintCard(text: String) {
    Spacer(modifier = Modifier.height(4.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
    Spacer(modifier = Modifier.height(4.dp))
}

/**
 * 单项授权的「开关行」：清晰呈现已授权（开）/ 未授权（关），点击整行或开关即前往系统设置页
 * 开启或关闭（应用无法直接切换系统权限，统一由家长在系统页面操作）。
 *
 * 已授权项文案提示「可关闭 / 管理」，未授权项提示缺失后果——二者都复用装机流水线的
 * 操作闸门豁免 + 四级降级链，回去拨完开关自动续接。
 */
@Composable
private fun PermissionToggleRow(
    perm: SpecialPermission,
    granted: Boolean,
    onOpen: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = perm.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            // 无状态接口的厂商开关：isGranted 恒 false，若照搬「未开启 + 红色 + 关着的开关」，
            // 家长明明已在系统里打开，回来仍看到"未开启"，正是「明明开了还显示未开」的直观来源。
            // 这里改成中性态 + 如实说明，既不谎报已开启，也不冒充故障/待办。
            val noStateApi = !perm.detectable
            // 电池优化：厂商省电白名单与官方查询 API 互不联通（见上方状态卡注释）。
            // 与无状态 API 的厂商开关同属"不能谎报已开启，也不能误报未开启"的中性态。
            val batteryNoQuery = !granted && perm == SpecialPermission.BATTERY_OPTIMIZATION
            val stateText = when {
                granted -> "已开启"
                batteryNoQuery -> "请在设置页确认（厂商省电白名单系统查询不到）"
                noStateApi -> "请到设置页确认（系统不提供状态查询，已开启可忽略）"
                else -> "未开启"
            }
            Text(
                text = stateText,
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    granted -> MaterialTheme.colorScheme.primary
                    batteryNoQuery || noStateApi ->
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    else -> MaterialTheme.colorScheme.error
                }
            )
            Text(
                text = when {
                    granted -> "点击前往系统设置可关闭 / 管理"
                    batteryNoQuery ->
                        "若已在厂商电池页设为「允许后台运行 / 不受限制」，可忽略本行提示"
                    // 厂商开关行：泛泛的"去设置查看"说不清防清理到底要做哪几步
                    // （华为的多任务卡片加锁是手势、没有系统页面），装机向导里拼了
                    // 厂商指引（OneTimeConsentViewModel.cleanupShieldExtraFor），
                    // 这里与装机同一口径，"设置期间"补开不再退化成一句空话。
                    noStateApi -> RomSettings.cleanupShieldHint(RomSettings.vendorOfDevice())
                        ?: "点击前往本品牌的「自启动 / 后台运行」设置查看"
                    else -> perm.impact
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }
        // 可检测项：开关只作状态指示（onCheckedChange 为空），整行可点即前往系统设置；
        // 避免「点开关 + 点整行」双重触发同一导航。
        // 不可检测项不画"关着的开关"——那会被误读成未开启，改成一个中性的「去确认」提示，
        // 整行本就可点，不额外制造第二个可点区域。
        if (perm.detectable) {
            Switch(checked = granted, onCheckedChange = {})
        } else {
            Text(
                text = "去确认",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * 看屏授权（屏幕采集 / MediaProjection）开关行。
 *
 * MediaProjection 的令牌是进程内的，进程被杀 / 设备重启后必然作废，因此与「家长是否曾授权」分开呈现：
 * - 令牌就位 → 已授权（开）；
 * - 曾同意但令牌失效（重启后）→ 待重新授权；
 * - 从未同意 → 待授权。
 * 后两者提供「去授权 / 重新授权」按钮，由家长在系统确认框完成；授权后由页面自动复检。
 */
@Composable
private fun CaptureAuthRow(
    hasToken: Boolean,
    everGranted: Boolean,
    onAuthorize: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "实时看屏授权（屏幕采集）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            val (stateText, stateColor) = when {
                hasToken -> "已授权" to MaterialTheme.colorScheme.primary
                everGranted -> "待重新授权（重启后需确认）" to MaterialTheme.colorScheme.error
                else -> "待授权" to MaterialTheme.colorScheme.error
            }
            Text(stateText, style = MaterialTheme.typography.bodySmall, color = stateColor)
            Text(
                text = "家长实时看屏 / 录屏依赖它；使用前须由家长在系统确认一次，使用中不向孩子弹窗",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }
        if (hasToken) {
            Switch(checked = true, onCheckedChange = {})
        } else {
            Button(onClick = onAuthorize, modifier = Modifier.padding(start = 8.dp)) {
                Text(if (everGranted) "去重新授权" else "去授权")
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
 * 管控能力分层明示卡片。
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

/**
 * route kind（UI 字符串）↔ [SpecialPermission] 的双向映射。
 *
 * 注意 kind 是 UI 层字符串、perm.key 是数据层字符串，二者并不相等
 * （例如 [SpecialPermission.USAGE_STATS].key = "usageStats"，但本页路由 kind = "usage_stats"），
 * 所以这里显式列出，避免任何地方再出现"按 key 去查却永远匹配不上"的静默退化。
 */
private fun permOfKind(kind: String): SpecialPermission? = when (kind) {
    "overlay" -> SpecialPermission.OVERLAY
    "usage_stats" -> SpecialPermission.USAGE_STATS
    "accessibility" -> SpecialPermission.ACCESSIBILITY
    "battery_optimization" -> SpecialPermission.BATTERY_OPTIMIZATION
    "exact_alarm" -> SpecialPermission.EXACT_ALARM
    "app_notifications" -> SpecialPermission.APP_NOTIFICATIONS
    else -> null
}

private fun titleFor(kind: String): String {
    // 全部 detectable 授权项用目录里的 label 作标题，保证"总览/引导页/横幅"三处同名。
    permOfKind(kind)?.let { return it.label }
    return when (kind) {
        "device_admin" -> "设备管理员"
        "web_guard" -> "上网防护"
        "all" -> "授权总览与补齐"
        else -> "权限引导"
    }
}

private fun descriptionFor(kind: String, mode: ControlMode): String {
    permOfKind(kind)?.let { perm ->
        return "本项属于需要您在系统页面亲手确认的授权：${perm.impact} 开启后无需返回本页，" +
            "系统会自动复检并跳到下一项。"
    }
    return when (kind) {
        "device_admin" -> when (mode) {
            ControlMode.LEGACY -> "设备管理员是系统级权限，授予后本 App 才能锁定屏幕、禁用相机、防卸载等。"
            ControlMode.DEVICE_ADMIN -> "你已激活基础设备管理员。锁屏、禁用相机已可用；更高权限（Kiosk、USB 限制）需升级为设备所有者。"
            else -> "你已激活设备所有者，全部管控能力可用。"
        }
        "web_guard" -> "上网防护用于拦截家长设置的违规网站（域名级过滤）。Android 要求这类「全设备网络过滤」必须由您亲手在系统确认框里允许一次，应用无法代您开启。"
        "all" -> "这里列出这台设备上全部需要您在系统页面确认的授权项。点「一键补齐」后，系统会逐项带您过去；每办完一项自动跳到下一项，无需手动返回或再点继续。"
        else -> "请按提示完成权限开启。"
    }
}

private fun stepsFor(kind: String, mode: ControlMode): List<String> {
    permOfKind(kind)?.let { perm ->
        return listOf(
            "点击下方「去开启（${perm.label}）」",
            "在系统设置中找到「护航成长」并开启对应开关",
            "返回本页面，已开启即表示成功，并会自动跳到下一项"
        )
    }
    return when (kind) {
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
        "web_guard" -> listOf(
            "点击下方「去授权」",
            "在系统弹窗中勾选「信任此应用」并点「确定」",
            "返回本页面，卡片显示「已授权」即生效",
            "过滤只作用于域名，无法看到页面内容（如实说明，不夸大）"
        )
        "all" -> listOf(
            "点「一键补齐剩余项」，进入与装机相同的自动接续流程",
            "能看到状态查询的开关，开完自动回到本页并跳下一项",
            "厂商「自启动 / 后台弹出界面」无状态接口，办完按返回即自动接续",
            "任何一项都可以稍后再来本页补开，不会被跳过就没了入口"
        )
        else -> emptyList()
    }
}
