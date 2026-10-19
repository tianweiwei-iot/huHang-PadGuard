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
    private val gateSuspension: GuardGateSuspension
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
        tryEmit(ConsentLaunch.Settings(key, intent))
        // 盯着这项：家长拨完开关，后台守护会自动把授权页拉回前台（免手动返回）
        consentReturn.watch(setOf(perm))
        // 豁免操作闸门对设置页的拦截：向导自己正带家长去设置页，
        // 否则刚启用的无障碍闸门会把设置页 1 秒内弹回，装机卡死。
        gateSuspension.suspendFor(SETTINGS_GATE_SUSPEND_MS)
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
        when (key) {
            KEY_DEVICE_ADMIN -> tryEmit(ConsentLaunch.DeviceAdmin(deviceAdminIntent()))
            KEY_RUNTIME -> {
                runtimeInFlight = true
                tryEmit(ConsentLaunch.Runtime(permissionGranter.missingRuntimePermissions()))
            }
            KEY_CAPTURE -> tryEmit(ConsentLaunch.MediaProjection)
            else -> SpecialPermission.fromKey(key)?.let { openAgain(it.key) }
        }
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
        if (!admin.isAdminActive()) {
            viewModelScope.launch {
                runCatching { authRepository.setDeviceAdminPromptAck() }
                    .onFailure { Logger.w(TAG) { "mark DA prompt ack failed" } }
            }
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
            _currentKey.value = KEY_DEVICE_ADMIN
            _phase.value = Phase.WAITING_PARENT
            tryEmit(ConsentLaunch.DeviceAdmin(deviceAdminIntent()))
            return
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

        // ③ 必须由家长在系统设置页手动打开的项
        val manual = missingManual()
        if (manual.isNotEmpty()) {
            val next = manual.first()
            // 刚自动跳出去的就是它、回来还是没开 → 停止自动循环，交给家长决定，
            // 否则在 ROM 不允许开启的机型上会陷入"返回→又跳回去"的无限循环。
            if (_currentKey.value == next.key) return
            _currentKey.value = next.key
            _phase.value = Phase.WAITING_PARENT
            val intent = SpecialPermission.openSettings(app, next)
            if (intent == null) {
                Logger.w(TAG) { "no settings entry for ${next.key}, skipping" }
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

        // ④ 屏幕采集：MediaProjection 令牌必须在真正用到之前拿到，
        //    否则家长第一次点"实时看屏"时孩子那边才弹确认框，场景直接失真。
        if (needsCapture() && KEY_CAPTURE !in requestedOnce) {
            requestedOnce += KEY_CAPTURE
            _currentKey.value = KEY_CAPTURE
            _phase.value = Phase.WAITING_PARENT
            tryEmit(ConsentLaunch.MediaProjection)
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
            _phase.value = Phase.DONE
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
                    title = if (mode == ControlMode.LEGACY) "设备管理员" else "设备管理员（已激活）",
                    impact = "锁屏、禁用相机、防止孩子卸载这些核心管控都依赖它",
                    source = ConsentItem.Source.DEVICE_ADMIN,
                    state = when {
                        mode != ControlMode.LEGACY -> ConsentItem.State.DONE
                        current == KEY_DEVICE_ADMIN -> ConsentItem.State.NEED_MANUAL
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

    private fun deviceAdminIntent(): Intent = deviceAdminRequest.intent(admin)

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
