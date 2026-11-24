package com.padguard.child.permission

import android.app.Application
import android.content.Intent
import com.padguard.child.capture.CaptureConsent
import com.padguard.child.capture.ScreenCaptureSession
import com.padguard.child.capture.ScreenCaptureService
import com.padguard.child.guard.GuardGate
import com.padguard.child.guard.GuardGateSuspension
import com.padguard.child.guard.GuardPasswordManager
import com.padguard.child.guard.WebGuardVpnController
import com.padguard.core.common.Logger
import com.padguard.core.data.model.policy.ControlMode
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.data.repository.ReauthSource
import com.padguard.core.data.repository.PolicyRepository
import com.padguard.core.engine.admin.DeviceAdminBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * App 内「权限补开」页的数据源。
 *
 * 装机的一次性授权走 [com.padguard.child.consent.OneTimeConsentViewModel]；
 * 这个 ViewModel 只服务于**装机之后**的场景：家长在主页发现某项没开，点进来补一下。
 * 它刻意保持很小——只读状态 + 提供跳转所需的意图，**不另写一套流水线**：
 * 「一键补齐」只是置位重授权标记，由入口分流切回同一条自动接续流水线，
 * 避免补开页与装机页各自演化成两种体验（历史上正是如此）。
 */
@HiltViewModel
class PermissionGuideViewModel @Inject constructor(
    private val app: Application,
    private val admin: DeviceAdminBridge,
    private val guardPassword: GuardPasswordManager,
    private val deviceAdminRequest: DeviceAdminRequest,
    private val webGuardVpn: WebGuardVpnController,
    private val policyRepository: PolicyRepository,
    private val authRepository: AuthRepository,
    private val gateSuspension: GuardGateSuspension,
    private val permissionGranter: PermissionGranter
) : androidx.lifecycle.ViewModel() {

    init {
        admin.refreshControlMode()
    }

    /** 当前管控身份 */
    val controlMode: StateFlow<ControlMode> = admin.controlMode

    /**
     * 从系统设置页返回后重新核对管控身份（设备管理员激活、DO 升级等）。
     * 系统设置页不回传任何回调，只能靠页面重新可见来复检，
     * 与装机授权流水线「拨完开关自动跳下一项」同源。
     */
    fun refresh() {
        admin.refreshControlMode()
    }

    /** 成为设备所有者（最高权限）的预置指引 */
    val provisioning = ProvisioningGuide(admin.adminComponent, app.packageName)

    /** 硬控闸门：未解锁则拉起密码框并放行原动作（返回 false） */
    fun ensureGuardUnlocked(): Boolean = GuardGate.ensureUnlocked(app, guardPassword)

    fun deviceAdminIntent(): android.content.Intent = deviceAdminRequest.intent(admin)

    // ==================== 授权总览 / 一键补齐 ====================

    /**
     * 本机**仍待补齐**的授权项：只含「可检测、且系统明确判定为未授予」的项。
     *
     * 这里刻意用 `includeUndetectable = false` 的待补齐口径，而不是装机流水线的
     * 全量口径：厂商私有开关（华为/小米的「自启动 / 后台弹出界面」）没有公开查询接口，
     * `isGranted` 恒为 false，若算进本清单，在定制 ROM 上本方法**永远非空** ——
     * 于是「一键补齐」按钮永不消失、总览永远走不到"全部已开启"、从任一设置页返回
     * 都会触发 [startConsentReview] 把家长反复拉回授权流水线。这正是「权限明明开了
     * 还显示未开 / 反复提示」的根因。
     *
     * 这类项不冒充待办，改由 [noStateApiAuthorizations] 单独如实呈现
     * 「需到设置页确认（系统不提供状态查询）」；装机首次引导仍走全量口径，
     * 由流水线带家长去设置页确认一次，不受本方法影响。
     */
    fun missingAuthorizations(): List<SpecialPermission> =
        SpecialPermission.missing(app, admin.hasOwnerPrivileges(), includeUndetectable = false)

    /**
     * 本机适用、但**平台不提供状态查询接口**的厂商私有开关。
     *
     * 用途仅限"如实告知家长有这项开关、需要他自己到设置页确认一次"：
     * 既不能谎报已开启，也不能算进 [missingAuthorizations] 的待办数。
     * 原生 Android / 未知品牌上为空。
     */
    fun noStateApiAuthorizations(): List<SpecialPermission> =
        applicableAuthorizations().filter { !it.detectable }

    /**
     * 本机全部适用的授权项（含已开启的），供「授权总览」完整展示 ——
     * 只列缺失项会让家长根本不知道这台设备上一共有哪些开关。
     */
    fun applicableAuthorizations(): List<SpecialPermission> =
        SpecialPermission.applicable(app, admin.hasOwnerPrivileges())

    fun isAuthorizationGranted(perm: SpecialPermission): Boolean = perm.isGranted(app)

    // ==================== 系统运行时权限 ====================

    /**
     * 当前仍未授予的**系统运行时权限**（中文展示名）。
     *
     * 与 [missingAuthorizations] 分列，因为它属于完全不同的口径：
     * - [missingAuthorizations] 是「待补齐」的特殊权限，驱动「一键补齐」按钮与自动接续；
     * - 这里可能包含家长装机时**主动拒绝**的项（非 DO 设备无静默补授，会一直缺失）。
     *   [com.padguard.child.permission.AuthorizationKeeper] 已按「曾授予又丢」纪律
     *   不再因这类项反复把家长拉回授权页，但也不能就此静默吞掉 —— 如实单列，
     *   由家长决定要不要去应用详情页补开，且**不驱动任何自动跳转**。
     */
    fun missingRuntimeAuthorizations(): List<String> =
        permissionGranter.missingRuntimePermissions().map(permissionGranter::labelOf)

    /**
     * 应用详情页：所有 Android 版本 / 所有厂商恒存在的官方页，
     * 从那里能开「权限 / 通知 / 电池」等任意一项系统权限。
     *
     * 与 [openSettingsFor] 同一套操作闸门豁免：设置页在闸门启用时会被拦截弹回，
     * 缺了豁免家长就会看到「点去开启，页面闪一下又回来」。
     */
    fun openAppDetails(): Intent {
        gateSuspension.suspendFor(
            SETTINGS_GATE_SUSPEND_MS,
            GuardGateSuspension.OWNER_PERMISSION_GUIDE
        )
        return RomSettings.appDetailsIntent(app)
    }

    // ==================== 看屏授权（屏幕采集 / MediaProjection）====================

    /**
     * 当前是否已持有可用的屏幕采集令牌（进程内）。
     *
     * MediaProjection 令牌是**进程内**的 IBinder：进程被杀 / 设备重启后必然作废，
     * 因此本值只反映"此刻能否直接看屏"，不等于"家长是否曾授权过"。
     * 补授权入口据此区分"已授权 / 重启后待重新授权 / 待授权"三态。
     */
    fun isCaptureAuthorized(): Boolean = ScreenCaptureSession.hasToken()

    /**
     * 家长装机时是否曾同意过屏幕采集。
     *
     * 配合 [isCaptureAuthorized] 区分：曾同意但令牌失效（重启后）= "待重新授权"；
     * 从未同意 = "待授权"。两者都需要家长去补授权入口重新确认一次系统弹窗。
     */
    fun captureEverGranted(): Boolean = CaptureConsent.granted(app)

    /**
     * 构建"仅补令牌、不采集"的暖令牌意图，供补授权入口重新取得 MediaProjection 令牌。
     *
     * 与装机一次性授权流水线用的是同一个意图类型：授权页据此只存令牌、不启动任何采集任务，
     * 避免"为补个授权结果平白无故开始录屏"。返回后由页面 ON_RESUME 自动复检令牌状态。
     */
    fun captureConsentIntent(): Intent = ScreenCaptureService.warmupIntent(app)

    /**
     * 打开某 [SpecialPermission] 的系统设置页。
     *
     * 与装机一次性授权流水线**同一套闸门豁免**：带家长去开启「无障碍服务」时，
     * 若操作密码闸门本身是靠这个无障碍服务实现的，它会在家长刚进设置页时把页面弹回来，
     * 表现就是"点去开启，设置页闪一下又回到引导页、开关怎么都拨不动"。
     * 这里先 [GuardGateSuspension.suspendFor] 开一个足够长的豁免窗口，
     * 让家长从容拨完开关再返回，回来由页面 ON_RESUME 自动复检并跳下一项。
     * 窗口随本次打开续期，离开引导页时 [endSettingsSession] 收口。
     */
    fun openSettingsFor(perm: SpecialPermission): android.content.Intent? {
        gateSuspension.suspendFor(
            SETTINGS_GATE_SUSPEND_MS,
            GuardGateSuspension.OWNER_PERMISSION_GUIDE
        )
        return SpecialPermission.openSettings(app, perm)
    }

    /**
     * 离开授权引导页时收口**本页**的操作闸门豁免，避免豁免窗口残留。
     * 多次调用幂等。
     *
     * 必须带持有者：本页的 `onDispose` 与「一键补齐」切到 OneTimeConsentScreen
     * 发生在同一帧，流水线可能已经为本轮设置页开好豁免。无条件 `clear()` 会把
     * 别人刚开的窗口抹掉，家长进设置页立刻被闸门顶回（R34）。
     * 流水线自己的收尾走 `clearAll()`，不受此处影响。
     */
    fun endSettingsSession() {
        gateSuspension.clear(GuardGateSuspension.OWNER_PERMISSION_GUIDE)
    }

    /**
     * 一键补齐：置位「需重新授权」，由 [com.padguard.child.MainActivity] 的入口分流
     * 直接切回一次性授权页（`autoStart = true`），**复用同一条自动接续流水线**。
     *
     * 为什么不在这里另写一套跳转链：补开页若自己逐个 startActivity，就得再实现一遍
     * 轮询回前台、防循环、闸门豁免、跳过出口 —— 两套流水线必然演化成两种体验，
     * 正是历史上「补开页要手动返回、装机页能自动跳」的根因。
     * DataStore 写入后 Flow 立即重组，无需重启 Activity，也无需家长再点一次「继续」。
     */
    fun startConsentReview() {
        viewModelScope.launch {
            runCatching { authRepository.setReauthRequired(ReauthSource.MANUAL_REVIEW) }
                .onFailure { Logger.w(TAG) { "request consent review failed: ${it.message}" } }
        }
    }

    // ==================== 上网防护（VPN）====================

    /** 系统是否已授予 VPN 权限（决定按钮文案与"已开启"标记）。 */
    fun isWebGuardPrepared(): Boolean = webGuardVpn.isPrepared()

    /** 拉起系统 VPN 确认框；已授权时返回 null。 */
    fun prepareVpnIntent(): android.content.Intent? = webGuardVpn.prepareIntent()

    /**
     * 授权回来后立刻按当前策略对账一次，免等下一轮自检（最长要几分钟）。
     *
     * 家长刚点完"允许"，如果界面上"已开启"却要等几分钟才真正开始过滤，
     * 会被当成"授权没生效"。这里主动拉一次，做到所见即所得。
     */
    fun syncWebGuardNow() {
        viewModelScope.launch {
            runCatching {
                val web = policyRepository.getPolicy().web
                val state = webGuardVpn.sync(web)
                Logger.i(TAG) { "web guard vpn synced after consent: $state" }
            }.onFailure { Logger.w(TAG, it) { "web guard vpn sync after consent failed" } }
        }
    }

    companion object {
        private const val TAG = "PermissionGuideVM"

        /**
         * 打开系统设置页时的操作闸门豁免窗口。
         * 覆盖"家长找到开关 + 拨完 + 自动回前台"的完整过程（轮询预算约 60s），
         * 10 分钟远大于最坏情况；离开引导页时 [endSettingsSession] 立即收口。
         * 与 [com.padguard.child.consent.OneTimeConsentViewModel] 的窗口口径一致。
         */
        private const val SETTINGS_GATE_SUSPEND_MS = 10 * 60 * 1000L
    }
}
