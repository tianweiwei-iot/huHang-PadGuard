package com.padguard.child.consent

import android.app.Application
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.child.capture.CaptureConsent
import com.padguard.child.capture.ScreenCaptureSession
import com.padguard.child.guard.GuardGateSuspension
import com.padguard.child.permission.DeviceAdminRequest
import com.padguard.child.permission.PermissionGranter
import com.padguard.child.permission.SpecialPermission
import com.padguard.child.consent.ConsentReturnPresenter
import com.padguard.core.common.Logger
import com.padguard.core.data.model.policy.ControlMode
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.engine.admin.DeviceAdminBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 一次性授权的编排中枢。
 *
 * ## 设计目标（家长侧体验）
 * 家长在装机时**只点一次**「同意并授权」，之后：
 * - App 能静默办的全部当场办完，不弹任何框；
 * - 系统规定必须人工确认的（系统权限弹窗 / 管理页 / 设置页开关 / 录屏确认），
 *   由本编排按依赖顺序**自动逐个带过去**，回来立刻复检、自动接下一项；
 * - 全部就绪→写记账→直接放行。**正常使用期间不会再出现第二个授权界面。**
 *
 * ## 为什么能做到"一次"
 * 关键在于把原本散落在五个界面的授权（权限引导页 / 设备管理员门禁 / AppOps 补开页 /
 * 协议弹窗 / 录屏授权页）收编成同一条流水线；每项该怎么抓是一致的模型（[ConsentItem]），
 * 由唯一的推进器 [advance] 决定下一步，而不是各自判定。
 *
 * ## Android 的硬边界（不可逾越，决定了本流水线必然包含若干次系统交互）
 * - AppOps（使用情况访问、悬浮窗、安装未知应用）与**无障碍服务**：
 *   连 Device Owner 都无权代开，只有用户在系统设置页亲手拨开关；
 * - 屏幕采集（MediaProjection）：隐私红线，必须用户点一次「立即开始」；
 * - 设备管理员：系统确认页点一次「激活」；
 * - 设备所有者(DO)：adb / 开机向导预置，**App 无法自提**——因此它被降级为可选增强，
 *   不进装机必须清单（DA 已能覆盖主能力）。
 *
 * 也就是说："一次确认"指的是**在 App 内只按一个按钮**，而不是"系统不弹任何东西"。
 * 系统那一层由 Android 强制，任何声称完全静默的实现都不可信。
 *
 * ## 为什么不会变成"点到一半卡住"或"来回弹同一个框"
 * 每次从系统页面返回都会复检确实有进展才继续自动跳；若某项回来仍是缺失态，
 * 就停在清单页把这一项标成 [ConsentItem.State.NEED_MANUAL]，
 * 家长可以继续也可以选择跳过[finishAnyway]——两个出口都在，绝不悬空。
 */
@HiltViewModel
class OneTimeConsentViewModel @Inject constructor(
    private val app: Application,
    private val permissionGranter: PermissionGranter,
    private val admin: DeviceAdminBridge,
    private val authRepository: AuthRepository,
    private val deviceAdminRequest: DeviceAdminRequest,
    private val consentReturn: ConsentReturnPresenter,
    private val gateSuspension: GuardGateSuspension,
    /**
     * 上网防护（本地 DNS 过滤 VPN）控制器。
     *
     * 装机会在这里申请一次 VPN 授权：Android 对"全设备流量通道"强制要求用户确认，
     * 而 App **无法从后台弹那个框**。若装机时不拿到，等到家长后来开启「网址拦截 / 白名单上网」，
     * 通道就永远起不来（要孩子拿到平板手动点一次才行），策略等于空转。
     */
    private val webGuardVpn: com.padguard.child.guard.WebGuardVpnController
) : ViewModel() {

    enum class Phase {
        /** 展示清单，等家长点「同意并授权」 */
        IDLE,
        /** App 正在后台静默办理（DO/PO 才有这一步） */
        RUNNING,
        /** 已交给家长在系统页面处理，回来自动推进 */
        WAITING_PARENT,
        DONE,
        ERROR
    }

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _items = MutableStateFlow<List<ConsentItem>>(emptyList())
    val items: StateFlow<List<ConsentItem>> = _items.asStateFlow()

    /**
     * 正在处理的那一项（供 UI 高亮），同时承担防循环职责：
     * 从系统页面回来后，若这一项仍判定为缺失，就停止自动跳转、把决定权交回家长。
     */
    private val _currentKey = MutableStateFlow<String?>(null)
    val currentKey: StateFlow<String?> = _currentKey.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _launch = MutableSharedFlow<ConsentLaunch>(extraBufferCapacity = 2)
    val launch: SharedFlow<ConsentLaunch> = _launch.asSharedFlow()

    /** 当前管控身份，供 UI 解释为什么还需要设备管理员这一步 */
    val controlMode: StateFlow<ControlMode> = admin.controlMode

    /** 家长是否已点过「同意并授权」 */
    private var started = false

    /** 对外暴露：授权链是否已启动（入口续跑、自动授权场景据此判断是否仍需自动触发） */
    val hasStarted: Boolean get() = started

    /** 每一类系统交互只自动发起一次：家长明确拒绝后不再纠缠 */
    private val requestedOnce = mutableSetOf<String>()

    /** 本机确实没有入口、或家长选择不再处理的项 */
    private val skippedKeys = mutableSetOf<String>()

    /**
     * 已经**带家长去过一次**的项。
     *
     * 与 [skippedKeys] 分开记：跳过是"家长明确不要了"，去过但没开成是"试过、没成功"。
     * UI 据此把行状态从悬空的「待处理」改成如实的「未开启（可再次尝试）」——
     * 否则家长看到一项永远停在灰点上，会以为是自己操作错了。
     */
    private val attemptedKeys = mutableSetOf<String>()

    /**
     * 设备管理员"已尽力但未激活"的标记是否已落库。
     *
     * 必须落库而不是只记在内存里：入口分流（[com.padguard.child.MainActivity]）是
     * `!isAdminActive() && !daPromptAck` 就强制回到本页。若设备上根本点不了「激活」
     * （ROM 阉割 / 已有其它管理员），这个条件会**每次都把家长弹回授权页**，
     * 家长看到的就是"这一项怎么都授权不了，还退不出去"。
     * 因此只要完整走完一趟仍未激活，就落一次"已知晓"，把决定权还给家长，
     * 同时在清单里保留「去激活」入口，随时可以补开。
     */
    private var daGiveUpWritten = false

    /**
     * 系统运行时权限弹窗序列进行中（结果回调尚未送达）。
     *
     * `RequestMultiplePermissions` 会按权限组一枚一枚地弹（定位 → 相机 → 麦克风 → 通知），
     * 部分 ROM 在相邻两枚弹窗交替时会让本页面短暂 `onResume`。此时 [onReturnedToApp]
     * 若照常推进，就会在家长还没答完权限时把第 ③ 步（系统设置页）提前拉起来 ——
     * 表现为"权限弹窗答到一半，界面自己跳到别的设置页"，正是授权跳转混乱的主因之一。
     *
     * 运行时权限的申请结果由 [onRuntimePermissionsResult] 保证回调（系统契约，拒绝也回调），
     * 因此这期间拒绝一切基于 `onResume` 的推进是安全的：不依赖 ROM 的行为差异。
     */
    private var runtimeInFlight = false

    init {
        admin.refreshControlMode()
        refreshItemStates()
    }

    // ==================== 家长-facing 入口 ====================

    /**
     * 唯一的「同意并授权」。
     *
     * 协议同意在这里一次性落库（无论后续系统授权是否顺利），
     * 之后主页的协议弹窗不会再出现——协议与授权合并成一次表达。
     */
    fun consent() {
        if (started) return
        started = true
        _message.value = null
        viewModelScope.launch {
            runCatching { permissionGranter.persistAgreement() }
                .onFailure { Logger.w(TAG) { "persist agreement failed: ${it.message}" } }
        }
        advance("consent-clicked")
    }

    /** 运行时权限弹窗的整体结果。被拒只降级对应能力，不阻断装机。 */
    fun onRuntimePermissionsResult(result: Map<String, Boolean>) {
        runtimeInFlight = false
        val denied = result.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            val names = denied.map { permissionGranter.labelOf(it) }
            Logger.w(TAG) { "runtime permissions denied: $names (degraded)" }
            _message.value = "已跳过：${names.joinToString("、")}，相关功能将不可用"
        }
        advance("runtime-result")
    }

    /**
     * VPN 授权确认框的结果。
     *
     * 家长点"取消"**不阻断装机**（这是隐私权限，拒绝是合理选择），
     * 但必须明确告知后果并记为跳过：之后家长端开启「网址拦截」时，
     * 设备侧不会静默假装在拦 —— 而是如实停在"未授权"态，等家长来补授权。
     *
     * 这里不看 result 参数，一律以真实的 `isPrepared()` 为准：
     * 部分 ROM 在已授权时也回传 RESULT_CANCELED，以系统状态为唯一真相源。
     */
    fun onVpnConsentResult() {
        if (!webGuardVpn.isPrepared()) {
            skippedKeys += KEY_VPN
            _message.value = "已跳过「上网防护」：家长端开启网址拦截后本项需补授权才会生效"
            Logger.w(TAG) { "vpn consent denied/skipped" }
        }
        advance("vpn-consent-result")
    }

    /**
     * 页面回到前台（从系统设置页 / 设备管理员确认页 / 录屏授权页返回）。
     *
     * AppOps、无障碍开关的变更**没有任何系统回调**，只能靠页面重新可见来复检。
     * 少了这一步，家长就得"开完再点一次继续"，谈不上一次确认。
     *
     * ⚠️ 这里**刻意不清空 [currentKey]**：`advance` 要靠它判断
     * "刚带家长去的就是这一项、回来却还是没开"，据此停止自动跳转，
     * 否则会在不允许开启该开关的 ROM 上变成"返回→又跳回去"的无限循环。
     *
     * 系统权限弹窗序列进行中（[runtimeInFlight]）一律不推进：那期间的 `onResume`
     * 只是弹窗交替的副产品，推进就会把家长从"答权限"直接扔到"下一个设置页"。
     */
    fun onReturnedToApp() {
        if (!started || _phase.value != Phase.WAITING_PARENT) return
        if (runtimeInFlight) return
        // 授权页已回到前台：撤掉"返回引导"通知，并停掉后台盯守（advance 会按需重新挂上）
        consentReturn.cancelNotification()
        advance("returned-to-app")
    }

    /**
     * 家长在清单里点某一项：直接带他去办这一项（而不是"只能靠底部按钮猜下一步"）。
     *
     * 这一条是必要的：清单里同时存在多项待办时，家长想先办手上这件事很正常；
     * 之前行完全不可点，点了没任何反应，读起来就是"这项授权选不中 / 授权不了"。
     *
     * @param key [ConsentItem.key]；未知 key 直接忽略，不做任何跳转
     */
    fun openItem(key: String) {
        if (_phase.value == Phase.RUNNING) return
        // 还没点过「同意并授权」：点任意一行等同于开始，不再要求家长先找按钮
        if (!started) {
            consent()
            return
        }
        // 运行时权限那几行的 key 是权限名（如 android.permission.CAMERA），不是流水线 key。
        // 不在这里归一化，点上去就会走进"未知 key"分支 → 界面停在等待态、按钮点了也没反应。
        val target = if (key in permissionGranter.requiredPermissions) KEY_RUNTIME else key
        Logger.i(TAG) { "open item by tap: $key -> $target" }
        consentReturn.stop()
        _currentKey.value = target
        _phase.value = Phase.WAITING_PARENT
        launchFor(target)
    }

    /**
     * 清单按钮的统一动作：重新尝试当前正在等待的这一步。
     *
     * 收敛到这里而不是让 UI 按 key 各自分支：漏实现某一种 key 的直接后果就是
     * 家长按下按钮却毫无反应，而这个毛病在回归测试里极难发现。
     */
    fun retryCurrent() {
        if (!started) {
            consent()
            return
        }
        // 重新发起前先清掉上一轮盯守，避免旧目标误触发回前台
        consentReturn.stop()
        val key = _currentKey.value
        if (key == null) {
            advance("retry-without-current")
            return
        }
        Logger.i(TAG) { "retry current step: $key" }
        _phase.value = Phase.WAITING_PARENT
        launchFor(key)
    }

    /**
     * 按项类型发起对应的系统交互。
     *
     * 所有跳转都走这里，保证「清单点什么」和「底部按钮点什么」落到完全相同的代码路径 ——
     * 两条路各写一份是历史上"某项点了没反应"的固定来源。
     */
    private fun launchFor(key: String) {
        when (key) {
            KEY_DEVICE_ADMIN -> {
                attemptedKeys += KEY_DEVICE_ADMIN
                val intent = deviceAdminIntentOrNull()
                if (intent == null) {
                    _message.value = DA_NO_ENTRY_HINT
                    skippedKeys += KEY_DEVICE_ADMIN
                    _currentKey.value = null
                    advance("da-no-entry")
                    return
                }
                tryEmit(ConsentLaunch.DeviceAdmin(intent))
                // 与 advance 同口径：盯守激活，免去手动返回
                consentReturn.watch({ admin.isAdminActive() }, "设备管理员")
                gateSuspension.suspendFor(SETTINGS_GATE_SUSPEND_MS)
            }
            KEY_RUNTIME -> {
                runtimeInFlight = true
                tryEmit(ConsentLaunch.Runtime(permissionGranter.missingRuntimePermissions()))
            }
            KEY_CAPTURE -> {
                tryEmit(ConsentLaunch.MediaProjection)
                consentReturn.watch({ ScreenCaptureSession.hasToken() }, "屏幕采集")
                gateSuspension.suspendFor(SETTINGS_GATE_SUSPEND_MS)
            }
            KEY_VPN -> {
                val intent = if (webGuardVpn.isPrepared()) null else webGuardVpn.prepareIntent()
                if (intent == null) {
                    // 已是授权态（或刚刚被 DO always-on 代授权）：直接续跑，不再弹框
                    advance("vpn-already-prepared")
                } else {
                    tryEmit(ConsentLaunch.VpnPrepare(intent))
                    consentReturn.watch({ webGuardVpn.isPrepared() }, "上网防护")
                    gateSuspension.suspendFor(SETTINGS_GATE_SUSPEND_MS)
                }
            }
            else -> SpecialPermission.fromKey(key)?.let { openAgain(it.key) }
        }
    }

    /**
     * 家长在清单页点击某一项，重新去一次对应系统设置页。
     *
     * 只有在流水线因"回来仍是缺失态"而停下时才会用到，
     * 属于兜底的手动通道，不影响主流程的自动性。
     */
    fun openAgain(key: String) {
        val perm = SpecialPermission.fromKey(key) ?: return
        val intent = SpecialPermission.openSettings(app, perm)
        if (intent == null) {
            _message.value = "本机型未开放「${perm.label}」的设置入口"
            skippedKeys += key
            return
        }
        _currentKey.value = key
        _phase.value = Phase.WAITING_PARENT
        attemptedKeys += key
        tryEmit(ConsentLaunch.Settings(key, intent))
        // 盯着这项：家长拨完开关，后台守护会自动把授权页拉回前台（免手动返回）
        consentReturn.watch(setOf(perm))
        // 豁免操作闸门对设置页的拦截：向导自己正带家长去设置页，
        // 否则刚启用的无障碍闸门会把设置页 1 秒内弹回，装机卡死。
        gateSuspension.suspendFor(SETTINGS_GATE_SUSPEND_MS)
    }

    /**
     * 系统页面根本没打开（ROM 没有该 Activity / 被系统拦下）。
     *
     * 必须显式接住：`ActivityResultLauncher.launch()` 抛 ActivityNotFoundException
     * 会把整个授权页打崩，家长看到的是"点了一下，界面闪没了"，
     * 而清单还停在这一项——正是"这项授权点不动"的直接成因。
     */
    fun onLaunchFailed(key: String, reason: String) {
        Logger.e(TAG) { "launch failed for $key: $reason" }
        attemptedKeys += key
        _message.value = when (key) {
            KEY_DEVICE_ADMIN -> DA_NO_ENTRY_HINT
            else -> "无法打开系统授权页面（$reason），可手动到系统设置里开启「$key」"
        }
        if (key == KEY_DEVICE_ADMIN) skippedKeys += KEY_DEVICE_ADMIN
        _currentKey.value = null
        _phase.value = Phase.WAITING_PARENT
    }

    /**
     * 剩余项不再办理，直接进入应用。
     *
     * 必须留这个出口：个别 ROM 压根不提供某些开关，
     * 没有它家长会被永久卡在装机页、平板完全用不了。
     */
    fun finishAnyway() {
        // 家长在授权流程里跳过了设备管理员（管控基石）：记录已确认，
        // 避免下次冷启动又因「DA 未激活」强制弹回授权流程形成无限循环。
        // 已激活 DA 的出口（正常完成 DA 后跳过其余项）不受影响。
        if (!admin.isAdminActive()) markDeviceAdminGiveUp()
        // 走完（即使有跳过项）即清掉"需重新授权"标记，避免入口分流反复弹回
        viewModelScope.launch {
            runCatching { authRepository.clearReauthRequired() }
                .onFailure { Logger.w(TAG) { "clear reauth required failed" } }
        }
        skippedKeys.addAll(missingManualKeys())
        advance("parent-opt-out")
    }

    // ==================== 推进器 ====================

    /**
     * 唯一的推进器：找到**当前第一个未就绪环节**并处理，处理完再来一轮。
     *
     * 顺序是有讲究的：先管理身份 → 再运行时权限 → 再系统设置项 → 最后屏幕采集。
     * 先拿管理身份是因为 DO/PO 能把后面绝大多数运行时权限变成静默，
     * 顺序反了就等于白多弹好几次系统窗。
     */
    private fun advance(from: String) {
        if (!started) return
        // 进入新一轮前先清掉上一轮盯守；若本轮会再次跳设置页，下面会重新挂上
        consentReturn.stop()
        admin.refreshControlMode()
        refreshItemStates()
        Logger.i(TAG) { "advance(from=$from): mode=${admin.controlMode.value}" }

        // ① 管理身份：设备管理员是本 App 的管控基石（锁屏 / 禁相机 / 防卸载），
        //    且 Device Owner 能大幅简化后续步骤，所以放在最前面。
        if (admin.controlMode.value == ControlMode.LEGACY && KEY_DEVICE_ADMIN !in requestedOnce) {
            requestedOnce += KEY_DEVICE_ADMIN
            attemptedKeys += KEY_DEVICE_ADMIN
            _currentKey.value = KEY_DEVICE_ADMIN
            _phase.value = Phase.WAITING_PARENT
            val intent = deviceAdminIntentOrNull()
            if (intent == null) {
                // 本机没有可用的激活页（个别 ROM 阉割了 DeviceAdminAdd）：
                // 硬 launch 会抛 ActivityNotFoundException 把页面打崩，这里先降级为
                // 「跳过 + 明确告知手动路径」，保证后面 7 项照常自动走完。
                Logger.w(TAG) { "no device-admin activation activity, degrade to manual path" }
                _message.value = DA_NO_ENTRY_HINT
                skippedKeys += KEY_DEVICE_ADMIN
                _currentKey.value = null
                advance("da-no-entry")
                return
            }
            tryEmit(ConsentLaunch.DeviceAdmin(intent))
            // 盯守 DA 激活：部分 ROM 的确认页点完「激活」后既不自动回前台、也不回调 ActivityResult，
            // 这里轮询 isAdminActive，办妥即把授权页接回前台并自动接续下一项，免去手动返回。
            consentReturn.watch({ admin.isAdminActive() }, "设备管理员")
            gateSuspension.suspendFor(SETTINGS_GATE_SUSPEND_MS)
            return
        }

        // ① 已经带家长去过激活页、回来仍未激活 → 不再无限强制。
        //
        // 为什么必须落一次标记：入口分流（MainActivity）的条件是
        // `consentDone == false || (!isAdminActive() && !daPromptAck)`。
        // 若这台机器根本激活不了（ROM 阉割 / 已有其它管理员占用），家长会被
        // **每一次冷启动都弹回本页**，也就是"设备管理员这项怎么都授权不了，还退不出去"。
        // 落标记后：本页照常显示该项为可选未开启 + 保留「去激活」按钮，
        // 但不再把整个装机流程扣在这里。
        if (admin.controlMode.value == ControlMode.LEGACY && KEY_DEVICE_ADMIN in attemptedKeys) {
            markDeviceAdminGiveUp()
        }

        // ② 运行时权限
        //
        // 两条路径都必须先占住 KEY_RUNTIME 再动作：DO 机型上若静默授予持续失败
        // （例如某个 ROM 不允许 setPermissionGrantState），没有这个占位就会
        // 「advance → runSilentGrant → advance → …」无限自转，界面上表现为卡在"正在自动配置"。
        val missingRuntime = permissionGranter.missingRuntimePermissions()
        if (missingRuntime.isNotEmpty() && KEY_RUNTIME !in requestedOnce) {
            requestedOnce += KEY_RUNTIME
            _currentKey.value = KEY_RUNTIME
            if (admin.hasOwnerPrivileges()) {
                runSilentGrant()
            } else {
                _phase.value = Phase.WAITING_PARENT
                // 弹窗序列期间屏蔽 onResume 推进，答完由 onRuntimePermissionsResult 接力
                runtimeInFlight = true
                tryEmit(ConsentLaunch.Runtime(missingRuntime))
            }
            return
        }
        // 走到这里：权限已齐，或申请过一次仍缺（家长可能是故意拒绝）→ 不纠缠，降级继续

        // ③ 上网防护（VPN 授权）：系统确认框，一次点击
        val vpnIntent = if (webGuardVpn.isPrepared()) null else webGuardVpn.prepareIntent()
        if (vpnIntent != null && KEY_VPN !in requestedOnce) {
            requestedOnce += KEY_VPN
            _currentKey.value = KEY_VPN
            _phase.value = Phase.WAITING_PARENT
            tryEmit(ConsentLaunch.VpnPrepare(vpnIntent))
            // 盯守授权：个别 ROM 点完"确定"不回调 ActivityResult，轮询 isPrepared 把页面接回来
            consentReturn.watch({ webGuardVpn.isPrepared() }, "上网防护")
            gateSuspension.suspendFor(SETTINGS_GATE_SUSPEND_MS)
            return
        }

        // ④ 必须由家长在系统设置页手动打开的项
        val manual = missingManual()
        if (manual.isNotEmpty()) {
            // 刚带家长去的就是这一项、回来还是没开 → 停止自动循环，交给家长决定，
            // 否则在 ROM 不允许开启的机型上会陷入"返回→又跳回去"的无限循环。
            if (_currentKey.value != null && manual.any { it.key == _currentKey.value }) return
            // 接着**刚办完的那一项**往后排，而不是每次都从第一项重来：
            // 家长从清单里点了第 4 项去开，开完却被拽去第 1 项，是很明显的"乱跳"。
            // 当前项已就绪时它不在 missing 里，用它的序号作为游标即可。
            val cursor = SpecialPermission.fromKey(_currentKey.value.orEmpty())?.ordinal ?: -1
            val next = manual.firstOrNull { it.ordinal > cursor } ?: manual.first()
            _currentKey.value = next.key
            attemptedKeys += next.key
            _phase.value = Phase.WAITING_PARENT
            val intent = SpecialPermission.openSettings(app, next)
            if (intent == null) {
                Logger.w(TAG) { "no settings entry for ${next.key}, skipping" }
                // 静默跳过 = 家长以为流程走完了，事后发现功能不生效。必须如实说一句
                _message.value = "本机型未开放「${next.label}」的设置入口，已跳过；" +
                    "该能力暂时不可用，其余授权继续"
                skippedKeys += next.key
                _currentKey.value = null
                advance("settings-unavailable")
                return
            }
            tryEmit(ConsentLaunch.Settings(next.key, intent))
            // 盯着这项：家长拨完开关，后台守护自动把授权页拉回前台并跳下一项（免手动返回）
            consentReturn.watch(setOf(next))
            // 豁免操作闸门对设置页的拦截（窗口随每次跳转续期，向导结束在 finish() 里清除）
            gateSuspension.suspendFor(SETTINGS_GATE_SUSPEND_MS)
            return
        }

        // ⑤ 屏幕采集：MediaProjection 令牌必须在真正用到之前拿到，
        //    否则家长第一次点"实时看屏"时孩子那边才弹确认框，场景直接失真。
        if (needsCapture() && KEY_CAPTURE !in requestedOnce) {
            requestedOnce += KEY_CAPTURE
            _currentKey.value = KEY_CAPTURE
            _phase.value = Phase.WAITING_PARENT
            tryEmit(ConsentLaunch.MediaProjection)
            // 令牌拿到即接回前台并收尾（避免个别 ROM 的 MediaProjection 回调缺失导致停在等待态）
            consentReturn.watch({ ScreenCaptureSession.hasToken() }, "屏幕采集")
            gateSuspension.suspendFor(SETTINGS_GATE_SUSPEND_MS)
            return
        }

        finish()
    }

    /** DO/PO 专属：后台静默拿全部运行时权限 + 施加加固基线。 */
    private fun runSilentGrant() {
        _phase.value = Phase.RUNNING
        _currentKey.value = KEY_RUNTIME
        viewModelScope.launch {
            runCatching { permissionGranter.grantAll() }
                .onFailure { Logger.e(TAG, it) { "silent grant failed" } }
            _currentKey.value = null
            advance("silent-grant-done")
        }
    }

    private fun finish() {
        _currentKey.value = null
        runtimeInFlight = false
        // 授权收尾：停掉后台盯守、撤掉返回引导通知、恢复操作闸门对设置页的拦截，避免残留
        consentReturn.stop()
        consentReturn.cancelNotification()
        gateSuspension.clear()
        viewModelScope.launch {
            runCatching { authRepository.saveConsentDone() }
                .onFailure { Logger.e(TAG, it) { "save consent failed" } }
            // 走完引导即清掉"需重新授权"标记（授权回退自愈的闭环）
            runCatching { authRepository.clearReauthRequired() }
                .onFailure { Logger.w(TAG) { "clear reauth required failed" } }
            _phase.value = Phase.DONE
        }
    }

    /**
     * 设备管理员"已尽力但未激活"→ 落一次"已知晓"，不再让入口分流把家长弹回本页。
     *
     * 只在**确实带家长去过激活页**之后调用（`attemptedKeys` 判定），
     * 不会因为"还没走到这一步"就提前放弃。
     */
    private fun markDeviceAdminGiveUp() {
        if (daGiveUpWritten) return
        daGiveUpWritten = true
        Logger.w(TAG) { "device admin still inactive after attempt, stop forcing consent flow" }
        viewModelScope.launch {
            runCatching { authRepository.setDeviceAdminPromptAck() }
                .onFailure { Logger.w(TAG) { "mark DA prompt ack failed" } }
        }
    }

    // ==================== 清单状态 ====================

    /** 刷新每一项的状态。UI 只渲染，不做判定，避免两处口径不一致。 */
    private fun refreshItemStates() {
        val mode = admin.controlMode.value
        val hasToken = ScreenCaptureSession.hasToken()
        val missingRuntime = permissionGranter.missingRuntimePermissions()
        val manualKeys = missingManualKeys()
        val current = _currentKey.value

        val list = buildList {
            add(
                ConsentItem(
                    key = KEY_DEVICE_ADMIN,
                    title = if (mode == ControlMode.LEGACY) "设备管理员（可选增强）" else "设备管理员",
                    impact = if (mode == ControlMode.LEGACY) {
                        "锁屏、禁用相机、防卸载这类加固依赖它。未激活不影响主要管控，" +
                            "可在「我的 → 权限引导」随时补开"
                    } else {
                        "已激活：锁屏、禁用相机、防卸载等加固能力可用"
                    },
                    source = ConsentItem.Source.DEVICE_ADMIN,
                    optional = true,
                    state = when {
                        mode != ControlMode.LEGACY -> ConsentItem.State.DONE
                        current == KEY_DEVICE_ADMIN -> ConsentItem.State.NEED_MANUAL
                        // 去过激活页仍未激活 / 本机没有入口：如实标"未开启"，不再悬空在 PENDING，
                        // 否则家长会以为是流程卡住了，而实际上是他随时可以点回来补开
                        KEY_DEVICE_ADMIN in skippedKeys ||
                            KEY_DEVICE_ADMIN in attemptedKeys -> ConsentItem.State.SKIPPED
                        else -> ConsentItem.State.PENDING
                    }
                )
            )
            permissionGranter.requiredPermissions.forEach { perm ->
                add(
                    ConsentItem(
                        key = perm,
                        title = permissionGranter.labelOf(perm),
                        impact = RUNTIME_IMPACT[perm].orEmpty(),
                        source = ConsentItem.Source.RUNTIME,
                        state = when {
                            perm !in missingRuntime -> ConsentItem.State.DONE
                            current == KEY_RUNTIME -> ConsentItem.State.RUNNING
                            else -> ConsentItem.State.PENDING
                        }
                    )
                )
            }
            add(
                ConsentItem(
                    key = KEY_VPN,
                    title = "上网防护（网站过滤）",
                    impact = "家长端「禁访问某网站 / 只允许白名单上网 / 违规分类拦截」依赖它；" +
                        "现在不授权，家长之后开启这些开关也不会生效，需要再拿平板补一次授权",
                    source = ConsentItem.Source.VPN,
                    state = when {
                        webGuardVpn.isPrepared() -> ConsentItem.State.DONE
                        KEY_VPN in skippedKeys -> ConsentItem.State.SKIPPED
                        else -> ConsentItem.State.NEED_MANUAL
                    }
                )
            )
            SpecialPermission.values().forEach { perm ->
                add(
                    ConsentItem(
                        key = perm.key,
                        title = perm.label,
                        impact = perm.impact,
                        source = ConsentItem.Source.MANUAL_SETTING,
                        state = when {
                            perm.key !in manualKeys -> ConsentItem.State.DONE
                            perm.key in skippedKeys -> ConsentItem.State.SKIPPED
                            else -> ConsentItem.State.NEED_MANUAL
                        }
                    )
                )
            }
            add(
                ConsentItem(
                    key = KEY_CAPTURE,
                    title = "屏幕采集（实时看屏 / 截屏 / 录屏）",
                    impact = "家长查看实时画面、远程截屏录屏依赖它；装机时不确认，之后会在孩子用平板时突然弹窗",
                    source = ConsentItem.Source.MEDIA_PROJECTION,
                    state = when {
                        hasToken -> ConsentItem.State.DONE
                        KEY_CAPTURE in skippedKeys -> ConsentItem.State.SKIPPED
                        else -> ConsentItem.State.NEED_MANUAL
                    }
                )
            )
        }
        _items.value = list
    }

    /** 仍待家长去系统设置页处理的项（已按 SpecialPermission 声明顺序排列）。 */
    private fun missingManual(): List<SpecialPermission> =
        SpecialPermission.missing(app, admin.hasOwnerPrivileges())
            .filter { it.key !in skippedKeys }

    private fun missingManualKeys(): List<String> = missingManual().map { it.key }

    private fun needsCapture(): Boolean =
        !ScreenCaptureSession.hasToken() && CaptureConsent.shouldRequest(app)

    /**
     * 拿一个**确实能被系统打开**的设备管理员意图。
     *
     * 两级：
     * 1. 正常走 `ACTION_ADD_DEVICE_ADMIN`（系统确认页，点一次「激活」）；
     * 2. 该页在本机不可解析（ROM 阉割 / 被系统策略隐藏）→ 退到「安全」设置页，
     *    家长可在 设备管理器 里手动勾选；轮询判定用的是 `isAdminActive()`，
     *    手动激活同样能被识别并自动接续下一项，不需要额外分支。
     *
     * 返回 null 表示两条路都不可用：调用方必须**跳过而不是硬 launch** ——
     * `ActivityResultLauncher.launch()` 抛 ActivityNotFoundException 会直接打崩授权页。
     */
    private fun deviceAdminIntentOrNull(): Intent? {
        val primary = deviceAdminRequest.intent(admin)
        if (resolvable(primary)) return primary
        Logger.w(TAG) { "ACTION_ADD_DEVICE_ADMIN unresolvable, fallback to security settings" }
        val fallback = Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
        return if (resolvable(fallback)) fallback else null
    }

    private fun resolvable(intent: Intent): Boolean =
        runCatching { intent.resolveActivity(app.packageManager) }.getOrNull() != null

    private fun tryEmit(value: ConsentLaunch) {
        if (!_launch.tryEmit(value)) {
            Logger.e(TAG) { "launch event dropped: $value" }
        }
    }

    companion object {
        private const val TAG = "OneTimeConsentVM"
        const val KEY_DEVICE_ADMIN = "deviceAdmin"
        const val KEY_RUNTIME = "runtimePermissions"
        const val KEY_CAPTURE = "mediaProjection"
        /** 上网防护 VPN 授权（VpnService.prepare 确认框） */
        const val KEY_VPN = "webGuardVpn"

        /** 设备管理员无入口 / 未激活时的统一提示（含手动路径，家长不必猜） */
        const val DA_NO_ENTRY_HINT =
            "「设备管理员」未激活：本机没有可用的激活页面。可到 设置 → 安全 → 设备管理器 手动开启，" +
                "或在「我的 → 权限引导」补开。其余授权不受影响。"

        /**
         * 每次发起设置页跳转时的闸门豁免窗口时长。
         * 覆盖「家长找到开关 + 拨完 + 自动回前台」的完整过程（轮询预算约 60s），
         * 10 分钟远大于最坏情况；向导每跳一次续期一次，结束即 clear。
         */
        private const val SETTINGS_GATE_SUSPEND_MS = 10 * 60 * 1000L

        /** 运行时权限 → 缺失后果（家长语言） */
        private val RUNTIME_IMPACT = mapOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION to "查看孩子所在位置、地理围栏告警依赖它",
            android.Manifest.permission.ACCESS_COARSE_LOCATION to "查看孩子所在位置、地理围栏告警依赖它",
            android.Manifest.permission.CAMERA to "远程拍照（看看孩子周围情况）依赖它",
            android.Manifest.permission.RECORD_AUDIO to "远程听音（确认孩子周边环境音）依赖它",
            android.Manifest.permission.POST_NOTIFICATIONS to "守护通知与实时看屏的授权引导依赖它"
        )
    }
}
