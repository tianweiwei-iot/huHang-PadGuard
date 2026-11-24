package com.padguard.child.consent

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.child.capture.CaptureConsent
import com.padguard.child.capture.CaptureConsentPrompter
import com.padguard.child.capture.ScreenCaptureSession
import com.padguard.child.guard.GuardGateSuspension
import com.padguard.child.permission.AuthorizationLedger
import com.padguard.child.permission.DeviceAdminRequest
import com.padguard.child.permission.PermissionGranter
import com.padguard.child.permission.RomSettings
import com.padguard.child.permission.SpecialPermission
import com.padguard.child.service.GuardService
import com.padguard.child.consent.ConsentReturnPresenter
import com.padguard.core.common.Logger
import com.padguard.core.data.model.policy.ControlMode
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.engine.admin.DeviceAdminBridge
import com.padguard.core.engine.admin.OpResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val ledger: AuthorizationLedger,
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

    /**
     * 「卡住项」前台兜底轮询的唯一 Job（R29）。
     *
     * 旧实现每次 [handleStuckCurrent] 都裸 `viewModelScope.launch` 起一个约 5 秒的轮询，
     * 既不取消上一轮、也不校验自己仍是当前项：家长在这 8 秒内点了清单里另一项后，
     * 旧轮询一旦检测到权限已开就调 [advance]，而 advance 会 stop 掉刚挂的新盯守并重算清单，
     * 把家长手动选的跳转覆盖掉；两次返回还可并存两个轮询、一次返回被推进两遍。
     */
    @Volatile
    private var stuckPollJob: Job? = null

    /**
     * 真正的错误：系统页面打不开、本机型无可用入口、推进器抛异常。
     *
     * 与 [notice] 分开是必要的：早期实现把所有文案（包括"已跳过某能力""正在确认开关"）
     * 一律塞进本字段，而 UI 把本字段恒定渲染成 errorContainer 红底卡片。
     * 家长在授权全程看到的红字实际上大多是**正常降级/中性进度**，
     * 反馈出来就是"授权过程中一直报错"。红底只留给真正需要处置的错误。
     */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * 中性告知：跳过某项、复检进行中、复检未检测到变化。
     *
     * 这些都不是故障——按家长意愿跳过隐私权限是合理选择，ROM 状态回写有延迟也是常态。
     * 用中性色（secondaryContainer）呈现，避免被误读成"报错了"。
     */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /**
     * 跳转系统设置页**之前**给家长看的一句分步指引（打开页面后去哪儿找开关）。
     *
     * 为什么必须有：无障碍 / 电池优化这类"列表型"设置页不会直接落到本应用的开关上，
     * 家长要在几十项里自己找到「护航成长」——真机反馈"从电池优化和无障碍开始
     * 授权过程不流畅、逻辑不清"的主因就是**进了设置页不知道去哪、开完不知道回没回**。
     * 指引文本来自 [RomSettings.settingsStepsHint]（纯函数、按厂商适配），
     * 直达开关页的项（悬浮窗等）为 null = 不显示。
     */
    private val _guide = MutableStateFlow<String?>(null)
    val guide: StateFlow<String?> = _guide.asStateFlow()

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
     * 已带家长去过、但**平台不提供状态查询**的项（厂商自启动 / 后台弹出界面）。
     *
     * 这类开关 `isGranted` 恒 false，若不单独记一笔，[advance] 的「回来仍是缺失态」保护
     * 会永远停在它上面 —— 家长开完返回，界面却以为没办、不肯跳下一项。
     * 记入本集合后清单标 [ConsentItem.State.CONFIRMED]「已带您前往设置（请确认开启）」：
     * 既不谎称已开启，也不再计入剩余待办，流水线自动接续。
     *
     * 刻意只在本次流程内有效：进程被杀后重进引导会重新带家长去一次，
     * 比"上次可能根本没拨开关却当成办完了"安全。
     */
    private val confirmedKeys = mutableSetOf<String>()

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
     * 授权进度持久化（跨进程被杀后续跑，不再从头开始）。
     *
     * 授权要带家长逐项去系统设置页拨开关，孩子在设置页停留几十秒到几分钟是常态；
     * 华为 EMUI 等激进内存回收会直接杀掉后台进程——进程死后 started / requestedOnce /
     * skippedKeys / attemptedKeys / confirmedKeys 全清零，重进即从「同意并开始授权」重来，
     * 正是"整个授权流程还是存在严重问题"的典型表现。把"已办/已跳过/已带去过"集合落盘，
     * 重进时恢复，流水线从真正的第一项缺失项接续。
     */
    private val progressPrefs: SharedPreferences by lazy {
        app.getSharedPreferences(PROGRESS_PREFS, android.content.Context.MODE_PRIVATE)
    }

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

    /**
     * 已向系统页面发起来自授权流水线的跳转、正等待家长返回的标记。
     *
     * 系统设置页 / 设备管理员确认页 / 录屏确认页返回时，**两个信号都会到**：
     * `registerForActivityResult` 的 result 回调，以及本页 `onResume`
     * （[com.padguard.child.consent.ConsentReturnPresenter] 把页拉回前台也会触发）。
     * 二者都在主线程、有时序差，若都触发 [advance] 就等于"一次返回被处理了两次"——
     * 表现为刚开完一项就被当成"回来还没开"、提前进入 [handleStuckCurrent] 的轮询、
     * 甚至在某些时序下误跳过本就没开成的项，正是"自动接续不稳定"的根因。
     *
     * 用「发出跳转时置位、回来处理时消费」的单一标记去重：无论哪个信号先到，
     * 只有第一个被处理，第二个直接忽略。这是"一个授权完成后稳定自动接续下一项"的
     * 关键去抖，不依赖任何时序假设，也不影响家长后续真实的逐项返回。
     */
    private var returnExpected = false

    /**
     * 上一次真正消费返回信号的时间戳（[SystemClock.elapsedRealtime]，不受改系统时间影响）。
     *
     * 一次「从系统页返回」会先后触发 ActivityResult 回调与授权页 onResume 两个信号；
     * 第一个信号消费后 [advance] 已经给**下一项**重新武装了 [returnExpected]，
     * 第二信号若再来消费，就会把家长根本没去过的下一项当成「已从设置页返回」——
     * 电池优化 / 后台运行这类靠 [confirmedKeys] 收口的项因此被隔空标记 CONFIRMED
     * 并跳过，真实授权始终拿不到（用户反馈的"这三项授权不了"）。
     * 用短冷却窗口只吞掉同一返回事件的尾随信号，不碰家长后续的真实返回。
     */
    private var lastReturnConsumedAt = 0L

    /** 消费一次「等待返回」标记：首个返回信号置位→true 并复位；重复信号→false（直接忽略）。 */
    private fun consumeReturn(): Boolean {
        if (!returnExpected) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastReturnConsumedAt < RETURN_DEBOUNCE_MS) return false
        returnExpected = false
        lastReturnConsumedAt = now
        return true
    }

    init {
        admin.refreshControlMode()
        // 先恢复上一次（可能被系统杀掉的）授权进度，再刷新清单：重进后从断点续跑。
        loadProgress()
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
        // 已恢复过进度（进程被杀后重进）再点按钮：直接续跑，而不是"从头再来"。
        if (started) { advance("consent-resume"); return }
        started = true
        _message.value = null
        _notice.value = null
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
            _notice.value = "已跳过：${names.joinToString("、")}，相关功能将不可用"
        }
        runCatching { advance("runtime-result") }
            .onFailure { e -> Logger.e(TAG, e) { "runtime advance failed" } }
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
            _notice.value = "已跳过「上网防护」：家长端开启网址拦截后本项需补授权才会生效"
            Logger.w(TAG) { "vpn consent denied/skipped" }
        }
        runCatching { advance("vpn-consent-result") }
            .onFailure { e -> Logger.e(TAG, e) { "vpn advance failed" } }
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
        // 去抖：同一个"从系统页回来"事件会先后触发 ActivityResult 回调与 onResume 两次，
        // 只认第一次，避免一次返回被 advance 处理两遍（误判卡顿 / 误跳过）。
        if (!consumeReturn()) return
        // 授权页已回到前台：撤掉"返回引导"通知，并停掉后台盯守（advance 会按需重新挂上）
        consentReturn.cancelNotification()
        // 不可检测项：家长已从厂商设置页返回 = 这一趟办完了（开关状态由他自己确认）。
        // 必须在 advance 之前落账，否则下面会把它当成"回来仍是缺失态"而停住不跳下一项。
        val current = _currentKey.value
        val perm = current?.let { SpecialPermission.fromKey(it) }
        if (perm != null && !perm.detectable) {
            confirmedKeys += perm.key
            Logger.i(TAG) { "oem settings visited and returned: ${perm.key} (state not queryable)" }
        } else if (perm == SpecialPermission.BATTERY_OPTIMIZATION && !perm.isGranted(app)) {
            // 电池优化：厂商省电白名单（华为受保护应用/电源管理等）与官方
            // isIgnoringBatteryOptimizations 是两套互不联通的机制——家长在厂商页
            // 完成加白后查询 API 仍可能恒 false，若按"回来仍是缺失态"处理就会
            // 卡进复检-跳过的拉锯。按「已带往设置（请确认开启）」如实收口、立即继续：
            // 真开过系统白名单时 isGranted 转 true，清单会自动升级为「已完成」。
            confirmedKeys += perm.key
            Logger.i(TAG) { "battery settings visited and returned without queryable grant" }
        }
        runCatching { advance("returned-to-app") }
            .onFailure { e -> reportAdvanceFailure("returned-to-app", e) }
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
        // 与 retryCurrent 同理：改选了别的项，旧 currentKey 的卡住复检必须立即作废，
        // 否则它检测到权限已开时会覆盖家长刚选的新跳转。
        stuckPollJob?.cancel()
        stuckPollJob = null
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
        // 重新发起前先清掉上一轮盯守与卡住复检轮询：
        // 旧轮询的目标是上一轮的 currentKey，继续活着会在新跳转进行中抢着 advance，
        // 把家长刚打开的设置页当场弹掉（R29 修的是同族问题，这里是遗漏的孪生路径）。
        consentReturn.stop()
        stuckPollJob?.cancel()
        stuckPollJob = null
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
        // 系统确认框/弹窗类步骤没有"找开关"的问题，先清掉上一项残留的设置页指引，
        // 避免家长在弹运行时权限框时还看着无障碍的分步说明。
        _guide.value = null
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
                returnExpected = true
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
                returnExpected = true
                tryEmit(ConsentLaunch.MediaProjection)
                consentReturn.watch({ ScreenCaptureSession.hasToken() }, "屏幕采集")
                gateSuspension.suspendFor(SETTINGS_GATE_SUSPEND_MS)
            }
            KEY_VPN -> {
                val prepared = webGuardVpn.isPrepared()
                val intent = if (prepared) null else prepareVpnIntentOrNull()
                if (intent == null) {
                    if (!prepared) {
                        // 查询异常而非"已授权"：记一次跳过，避免下一轮 advance 再试导致空转
                        requestedOnce += KEY_VPN
                        skippedKeys += KEY_VPN
                        _notice.value = "无法读取上网防护的授权状态，本项已跳过；" +
                            "家长端开启网址拦截前需补授权"
                        Logger.w(TAG) { "vpn prepare unavailable on manual launch" }
                    }
                    // 已是授权态（或刚刚被 DO always-on 代授权）：直接续跑，不再弹框
                    advance("vpn-already-prepared")
                } else {
                    returnExpected = true
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
     * 与 [advanceInternal] 步骤④共用 [launchManualStep]：两条路若各写一份跳转，
     * 表现就是"从清单点进去能自动跳，底部按钮点进去却卡住"，回归测试极难发现。
     */
    fun openAgain(key: String) {
        val perm = SpecialPermission.fromKey(key) ?: return
        launchManualStep(perm)
    }

    /**
     * 带家长去某项系统设置页的**唯一**入口（自动推进与手动点击共用）。
     *
     * 跳转前先把分步指引写进 [guide]：无障碍 / 电池优化这类"列表型"页面
     * 不会直达本应用开关，家长进了设置页不知道去哪儿找，是"授权逻辑不清"
     * 投诉的直接来源。直达开关页的项指引为 null，自然清掉上一项的残留。
     *
     * @return false 表示本机型没有可用的设置入口（已按"如实跳过"收口并重新推进）
     */
    private fun launchManualStep(perm: SpecialPermission): Boolean {
        val intent = SpecialPermission.openSettings(app, perm)
        if (intent == null) {
            // 静默跳过 = 家长以为流程走完了，事后发现功能不生效。必须如实说一句
            _notice.value = "本机型未开放「${perm.label}」的设置入口，已跳过；" +
                "该能力暂时不可用，其余授权继续"
            // 这一项压根没有设置页可去，上一项残留的分步指引只会让家长照着不存在的页面找开关。
            _guide.value = null
            skippedKeys += perm.key
            _currentKey.value = null
            advance("settings-unavailable")
            return false
        }
        _guide.value = RomSettings.settingsStepsHint(RomSettings.vendorOfDevice(), perm.key)
        _currentKey.value = perm.key
        _phase.value = Phase.WAITING_PARENT
        attemptedKeys += perm.key
        returnExpected = true
        tryEmit(ConsentLaunch.Settings(perm.key, intent))
        // 可检测项：拨完开关轮询到"已授予"即自动接回并跳下一项；
        // 电池优化：官方查询与厂商白名单是两套机制，改盯"已授予 或 已从设置页返回"；
        // 不可检测项（厂商私有开关）：改盯"离开设置页后回到前台"，绝不谎报已开启。
        watchSettingsStep(perm)
        // 豁免操作闸门对设置页的拦截：向导自己正带家长去设置页，
        // 否则刚启用的无障碍闸门会把设置页 1 秒内弹回，装机卡死。
        gateSuspension.suspendFor(SETTINGS_GATE_SUSPEND_MS)
        saveProgress()
        return true
    }

    /**
     * 系统页面根本没打开（ROM 没有该 Activity / 被系统拦下）。
     *
     * 必须显式接住：`ActivityResultLauncher.launch()` 抛 ActivityNotFoundException
     * 会把整个授权页打崩，家长看到的是"点了一下，界面闪没了"，
     * 而清单还停在这一项——正是"这项授权点不动"的直接成因。
     */
    fun onLaunchFailed(key: String, reason: String) {
        // 异常原文（如 "ActivityNotFoundException: No Activity found to handle Intent..."）
        // 只写日志，绝不拼进家长可见文案。早期实现把它直接显示在红底错误卡上，
        // 家长看到的就是"一段英文提示"，既不知道哪项没开成、也不知道该怎么办。
        Logger.e(TAG) { "launch failed for $key: $reason" }
        // 页面根本没打开：复位"等待返回"标记，否则下一次真实从别的页回来会被去抖误吞
        returnExpected = false
        attemptedKeys += key
        _message.value = when (key) {
            KEY_DEVICE_ADMIN -> DA_NO_ENTRY_HINT
            KEY_RUNTIME -> "系统权限确认框没能打开。请点清单中的「运行时权限」重试；" +
                "若装有第三方权限管理软件，也可能拦截了弹窗。"
            KEY_VPN -> "上网防护的确认框没能打开。可点清单中的「上网防护」重试。"
            KEY_CAPTURE -> "屏幕采集授权页没能打开。可点清单中的「屏幕采集」重试。"
            else -> "「${labelOfStep(key)}」的设置页打不开。可到 系统设置 → 应用 → 护航成长 → 权限 " +
                "中手动开启，或点清单中的这一行重试。"
        }
        // launchManualStep 是"先发事件、再挂盯守"，而 launch() 抛异常是在 UI 侧稍后发生——
        // 走到这里时盯守、卡住复检、闸门豁免、指引卡多半已经挂上了。必须同一处全部撤掉，
        // 否则后台会白盯一个根本没打开的设置页 5 分钟，最后还弹一条过期的"回来继续"。
        consentReturn.stop()
        consentReturn.cancelNotification()
        stuckPollJob?.cancel()
        stuckPollJob = null
        _guide.value = null
        // 跳转前开了 10 分钟的操作闸门豁免（为让家长从容拨开关），页面没打开就必须立即撤销，
        // 否则等于白送一段"无需密码就能进设置页改管控相关开关"的窗口。
        // 用 clearAll 而非带 owner 的 clear：流水线自身的豁免不带 owner，且此处已无任何待跳步骤。
        gateSuspension.clearAll()
        // 运行时权限弹窗序列被中断：不复位会让后续所有 onReturnedToApp 都被 runtimeInFlight 挡住，
        // 家长点了「继续」也毫无反应（表现为"授权页死了"）。
        runtimeInFlight = false

        if (key == KEY_DEVICE_ADMIN) skippedKeys += KEY_DEVICE_ADMIN
        // ⚠️ 对所有 SpecialPermission 收口，不只是不可检测项。
        // 旧实现只处理 detectable==false：可检测项（悬浮窗 / 无障碍 / 电池优化等）launch 失败后
        // 既不在 skippedKeys 也不在 confirmedKeys，missingManual() 仍认它是待办——
        // 家长每点一次「继续」就重新拉起同一个打不开的页面，陷入"点继续没反应"的死循环。
        // 标记 skipped 是可逆的：清单里这一行仍可点，家长随时能重试。
        SpecialPermission.fromKey(key)?.let { skippedKeys += it.key }
        // 不可检测项的 isGranted 恒 false，还须按"已带往设置"落账，
        // 与正常返回时的 onReturnedToApp 同口径，避免清单永远停在 NEED_MANUAL。
        if (SpecialPermission.fromKey(key)?.detectable == false) confirmedKeys += key
        _currentKey.value = null
        _phase.value = Phase.WAITING_PARENT
        saveProgress()
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
            runCatching { authRepository.clearAllReauthRequired() }
                .onFailure { Logger.w(TAG) { "clear reauth required failed" } }
        }
        skippedKeys.addAll(missingManualKeys())
        saveProgress()
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
        // 任何一次推进都作废旧轮询：它盯的是上一轮的 currentKey，
        // 继续活着只会把新跳转覆盖回旧上下文（见 stuckPollJob 注释）。
        stuckPollJob?.cancel()
        stuckPollJob = null
        // 进入新一轮前先清掉上一轮盯守；若本轮会再次跳设置页，下面会重新挂上
        consentReturn.stop()
        admin.refreshControlMode()
        refreshItemStates()
        Logger.i(TAG) { "advance(from=$from): mode=${admin.controlMode.value}" }
        runCatching { advanceInternal(from) }
            .onFailure { e -> reportAdvanceFailure(from, e) }
        // 每轮状态变更后落盘，进程被杀后能从断点续跑（见 loadProgress）。
        saveProgress()
    }

    /**
     * 推进器异常的唯一收口。
     *
     * 旧文案"授权流程出现意外错误，请返回后重试或继续授权"没有告诉家长**卡在哪一步**、
     * **现在能做什么**，家长只能反复返回/点按钮，真机反馈就是"一直报错、走不下去"。
     * 这里带出当前步骤名和两条明确退路（重试本项 / 暂时跳过），错误本身仍写日志供排障。
     */
    private fun reportAdvanceFailure(from: String, e: Throwable) {
        Logger.e(TAG, e) { "advance failed from=$from" }
        _message.value = "「${labelOfStep(_currentKey.value)}」这一步没能自动完成。" +
            "可以点主按钮重试这一项，或用下方「暂时跳过」先进入使用。"
        _phase.value = Phase.ERROR
    }

    /** 把流水线内部 key 翻译成家长看得懂的步骤名，避免错误文案里冒出权限常量。 */
    private fun labelOfStep(key: String?): String = when (key) {
        KEY_DEVICE_ADMIN -> "设备管理员"
        KEY_RUNTIME -> "运行时权限"
        KEY_VPN -> "上网防护"
        KEY_CAPTURE -> "屏幕采集"
        null -> "授权准备"
        // 未知 key 也要给中文兜底，避免权限常量直接漏到家长界面上
        else -> SpecialPermission.fromKey(key)?.label ?: "该项授权"
    }

    /**
     * [advance] 的实际推进逻辑；任何异常都被外层 [advance] 捕获，
     * 绝不外泄到生命周期 / ActivityResult 回调里把整个授权页打崩（"中途闪退"的主因之一）。
     */
    @Suppress("CyclomaticComplexMethod")
    private fun advanceInternal(from: String) {
        // ① 管理身份：设备管理员是本 App 的管控基石（锁屏 / 禁相机 / 防卸载），
        //    且 Device Owner 能大幅简化后续步骤，所以放在最前面。
        if (admin.controlMode.value == ControlMode.LEGACY && !admin.isAdminActive() && KEY_DEVICE_ADMIN !in requestedOnce) {
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
            returnExpected = true
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
        //
        // prepare() 有三种结果，过去只区分了两种：已授权(null) / 需确认(intent)。
        // 系统 API 抛异常时两者都拿不到，旧代码把它当成"已授权"静默跳过——
        // 家长以为网址拦截已生效，实际从未授权。这里如实记为跳过并明示。
        val vpnPrepared = webGuardVpn.isPrepared()
        val vpnIntent = if (vpnPrepared) null else prepareVpnIntentOrNull()
        if (!vpnPrepared && vpnIntent == null && KEY_VPN !in requestedOnce) {
            requestedOnce += KEY_VPN
            skippedKeys += KEY_VPN
            _currentKey.value = null
            _notice.value = "无法读取上网防护的授权状态，本项已跳过；" +
                "家长端开启网址拦截前需补授权"
            Logger.w(TAG) { "vpn prepare unavailable, degraded to skipped" }
        } else if (vpnIntent != null && KEY_VPN !in requestedOnce) {
            requestedOnce += KEY_VPN
            _currentKey.value = KEY_VPN
            _phase.value = Phase.WAITING_PARENT
            // 与其余系统页跳转同口径：发跳转即武装返回标记。
            // 漏掉它时，靠 consentReturn 轮询把页面接回来的 ROM 上
            // onReturnedToApp 的 consumeReturn() 会把回调吞掉，流水线卡死在 VPN。
            returnExpected = true
            tryEmit(ConsentLaunch.VpnPrepare(vpnIntent))
            // 盯守授权：个别 ROM 点完"确定"不回调 ActivityResult，轮询 isPrepared 把页面接回来
            consentReturn.watch({ webGuardVpn.isPrepared() }, "上网防护")
            gateSuspension.suspendFor(SETTINGS_GATE_SUSPEND_MS)
            return
        }

        // ④ 必须由家长在系统设置页手动打开的项
        val manual = missingManual()
        if (manual.isNotEmpty()) {
            val curKey = _currentKey.value
            // 刚带家长去的就是这一项、回来还是没开：**不要立刻硬停下**。
            // ROM 对「已授予」状态的回写常有数百毫秒~数秒延迟，若此时直接停住，
            // 家长看到的就是"我明明开了，它却卡着不动"。先做有界复检，
            // 确实拨动即自动续跑；复检窗口到期仍缺失则如实跳过并继续，绝不悬空卡死。
            if (curKey != null && manual.any { it.key == curKey }) {
                val perm = SpecialPermission.fromKey(curKey)
                if (perm != null) {
                    handleStuckCurrent(perm)
                    return
                }
            }
            // 接着**刚办完的那一项**往后排，而不是每次都从第一项重来：
            // 家长从清单里点了第 4 项去开，开完却被拽去第 1 项，是很明显的"乱跳"。
            // 当前项已就绪时它不在 missing 里，用它的序号作为游标即可。
            val next = nextManualItemAfter(manual, _currentKey.value) ?: run {
                // manual 非空时 nextManualItemAfter 必返回首项，下列分支不可达；
                // 仅作防御，避免空指针导致授权页崩溃。
                Logger.w(TAG) { "next manual item unexpectedly null while manual non-empty, finishing" }
                finish()
                return
            }
            launchManualStep(next)
            return
        }

        // ⑤ 屏幕采集：MediaProjection 令牌必须在真正用到之前拿到，
        //    否则家长第一次点"实时看屏"时孩子那边才弹确认框，场景直接失真。
        if (needsCapture() && KEY_CAPTURE !in requestedOnce) {
            requestedOnce += KEY_CAPTURE
            _currentKey.value = KEY_CAPTURE
            _phase.value = Phase.WAITING_PARENT
            // 必须复用 [launchFor] 的 KEY_CAPTURE 分支，而不是在这里复制一份 launch：
            // 复制版曾漏掉 returnExpected = true，CapturePermissionActivity 确认后
            // onReturnedToApp 被 consumeReturn() 吞掉 → 不 advance、不 finish →
            // clearProgress() 不执行 → 电池优化/后台运行的 confirmedKeys 持久化残留，
            // 重进向导又被 missingManual() 过滤，表现为"这三项都授权不了"。
            // 与清单点击（openItem）/ 重试（retryCurrent）走同一条路，才不会再次漏武装。
            launchFor(KEY_CAPTURE)
            return
        }

        finish()
    }

    /**
     * 当前项返回后仍判定为缺失：**有界复检 + 到期当场决策**。
     *
     * 两种真实成因对应两种结果：
     * 1. **ROM 传播延迟**（已拨开关但状态未即时回写）→ 复检窗口内查到即自动续跑，
     *    这正是家长反馈"明明开了却不自动跳"的主因；
     * 2. **本机确实无此开关 / 家长没拨** → 复检窗口耗尽**立即**如实跳过、继续下一项。
     *
     * ⚠️ 到期必须当场推进。旧实现到期后只是无声停住，要等家长再触发两次返回、
     * 前后三趟约 24 秒的复检窗口才肯跳过——界面全程显示「正在确认…」却毫无动静，
     * 这正是真机测试反馈"返回后自动跳转的等待时间有点长"的直接来源。
     * 跳过是可逆的：清单里这一行仍可点，家长随时能重新去开。
     */
    private fun handleStuckCurrent(perm: SpecialPermission) {
        val key = perm.key
        _notice.value = "正在确认「${perm.label}」是否已开启…"
        // 前台兜底轮询：本页已回到前台，presenter 的"接回前台"在此场景是 no-op，
        // 故这里直接轮询真实状态，拨动即自动续跑（最多 STUCK_POLL_COUNT 次）。
        // 每次只保留一个轮询，且每拍都校验「自己仍是当前等待项」——
        // 家长中途改选了别的项 / 又触发一次返回时，旧轮询必须自行退出而不是抢着 advance。
        stuckPollJob?.cancel()
        stuckPollJob = viewModelScope.launch {
            repeat(STUCK_POLL_COUNT) {
                delay(STUCK_POLL_MS)
                if (_currentKey.value != key || _phase.value != Phase.WAITING_PARENT) return@launch
                if (perm.isGranted(app)) { advance("stuck-poll-granted"); return@launch }
            }
            if (_currentKey.value != key || _phase.value != Phase.WAITING_PARENT) return@launch
            // 复检窗口耗尽仍未授予：本机可能不支持该开关 / 家长没找到开关。
            // 先摘除自身引用再 advance——advance 开头会 cancel stuckPollJob，
            // 不摘除等于让推进过程跑在一具已取消的协程里（虽无挂起点，语义上也是地雷）。
            stuckPollJob = null
            Logger.w(TAG) { "recheck window expired without grant, skip and continue: $key" }
            _notice.value = "未检测到「${perm.label}」的状态变化：可能还没拨开关、或本机没有这一项。" +
                "可稍后点击清单中这一行重试；现在先继续下一项"
            skippedKeys += key
            _currentKey.value = null
            advance("stuck-window-expired")
        }
    }

    /** DO/PO 专属：后台静默拿全部运行时权限 + 施加加固基线。 */
    private fun runSilentGrant() {
        _phase.value = Phase.RUNNING
        _currentKey.value = KEY_RUNTIME
        viewModelScope.launch {
            runCatching { permissionGranter.grantAll() }
                .onSuccess { report -> reportSilentGrantProblems(report) }
                .onFailure { Logger.e(TAG, it) { "silent grant failed" } }
            _currentKey.value = null
            advance("silent-grant-done")
        }
    }

    /**
     * 静默授权的失败项必须变成家长看得见的事实。
     *
     * `GrantReport` 过去被整包丢弃：DO 下 `grantRuntimePermission` 失败、
     * `applyHardeningBaseline` 部分未生效时，日志里有一行汇总，界面却一切正常——
     * 家长以为防卸载/防改时间都已锁定，实际没有。这里把失败项汇总成中性告知
     * （可重试、可手动补开），同时保留日志供排障。
     */
    private fun reportSilentGrantProblems(report: PermissionGranter.GrantReport) {
        val problems = mutableListOf<String>()
        val failed = report.failed.map { permissionGranter.labelOf(it) }
        if (failed.isNotEmpty()) problems += "权限未授予：${failed.joinToString("、")}"
        val hardeningFailed = report.hardening
            .filter { it.second !is OpResult.Ok }
            .map { it.first }
        if (hardeningFailed.isNotEmpty()) problems += "加固未生效：${hardeningFailed.joinToString("、")}"
        if (problems.isEmpty()) return
        val text = problems.joinToString("；") + "。可点清单对应项重试。"
        _notice.value = text
        Logger.w(TAG) { "silent grant degraded: ${problems.joinToString("；")}" }
    }

    private fun finish() {
        _currentKey.value = null
        runtimeInFlight = false
        _guide.value = null
        // 整条流水线结束是权威收口：上一项残留的"跳过/复检中"提示不能跟到
        // 完成态继续挂在页面上，否则家长以为刚走完的那一步仍在报错。
        _message.value = null
        _notice.value = null
        // 授权收尾：停掉后台盯守、撤掉返回引导通知、恢复操作闸门对设置页的拦截，避免残留。
        // 用 clearAll：整条流水线结束是权威收口，此时窗口可能登记在补开页名下（R34 持有者模型），
        // 带 owner 的 clear() 会认不出而留下最长 10 分钟的设置页豁免。
        consentReturn.stop()
        consentReturn.cancelNotification()
        gateSuspension.clearAll()
        // 关键：先把"授权完成"标记落库，任何后续副作用失败都不能让流程卡在"没存完"、
        // 导致下次冷启动又从「同意并开始授权」重来（进程被杀后的"整个流程重置"主因）。
        viewModelScope.launch {
            runCatching { authRepository.saveConsentDone() }
                .onFailure { Logger.e(TAG, it) { "save consent failed" } }
            // 走完引导即清掉"需重新授权"标记（授权回退自愈的闭环）
            runCatching { authRepository.clearAllReauthRequired() }
                .onFailure { Logger.w(TAG) { "clear reauth required failed" } }
            // 授权正式完成：清掉断点续跑用的临时进度，避免下次进入被陈旧的 started 干扰
            // （否则重装/补授权场景会因 hasStarted==true 而不触发 autoStart 自动接回）。
            clearProgress()
            _phase.value = Phase.DONE
        }
        // 以下副作用失败不影响"已完成"结论，逐条兜底，绝不外泄异常。
        runCatching { GuardService.refreshPermissionNotice(app) }
        // 采集授权确实拿到令牌时，撤掉可能残留的引导通知（普通通知不随进程死亡消失）。
        // 只在 hasToken() 为真时撤：若这一项被跳过，那条通知仍是有用的，不能误清。
        if (ScreenCaptureSession.hasToken()) runCatching { CaptureConsentPrompter.clear(app) }
        // 把"这一趟确实开到了"写进账本：掉失自愈只认「曾授予又丢」。
        // 不能只靠 AuthorizationKeeper 周期自检来记账 —— 它要求已绑定，而装机授权发生在绑定之前，
        // 若这台设备一直没绑上，账本就永远是空的，日后掉了也不会被叫回来。
        // 主动跳过的项 isGranted=false，不会被记进去，符合"尊重家长选择"的口径。
        SpecialPermission.applicable(app, admin.hasOwnerPrivileges()).forEach { perm ->
            if (perm.detectable) runCatching { ledger.observe(perm.key, perm.isGranted(app)) }
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

    // ==================== 进度持久化（防进程被杀后从头重来） ====================

    /** 从磁盘恢复已办进度：进程被杀 / 任务被系统回收后重进，流水线从断点续跑。 */
    private fun loadProgress() {
        runCatching {
            val set = progressPrefs.getStringSet(KEY_PROGRESS_SET, emptySet()).orEmpty()
            skippedKeys += set.mapNotNull { if (it.startsWith(PREFIX_SKIP)) it.removePrefix(PREFIX_SKIP) else null }
            confirmedKeys += set.mapNotNull { if (it.startsWith(PREFIX_CONFIRM)) it.removePrefix(PREFIX_CONFIRM) else null }
            attemptedKeys += set.mapNotNull { if (it.startsWith(PREFIX_ATTEMPT)) it.removePrefix(PREFIX_ATTEMPT) else null }
            requestedOnce += set.mapNotNull { if (it.startsWith(PREFIX_REQUEST)) it.removePrefix(PREFIX_REQUEST) else null }
            if (set.isNotEmpty()) started = true
            // 进程死亡重入后，把"被系统中断（未完成）的系统授权"从 requestedOnce 剔除，使其本会话可重新请求。
            // 详见 [reconcileInterruptedRequests]。
            reconcileInterruptedRequests()
        }.onFailure { Logger.w(TAG) { "load consent progress failed: ${it.message}" } }
    }

    /**
     * 进程死亡重入后，把"被系统中断而从未完成"的系统授权从 [requestedOnce] 中剔除，
     * 让本会话可以重新请求——避免"授权静默缺失、且不再自动请求"。
     *
     * ## 背景
     * `KEY_RUNTIME` / `KEY_VPN` / `KEY_CAPTURE` 在弹出系统确认框**之前**就被计入 `requestedOnce`
     * 并随 [saveProgress] 落盘。这是为了让"等待结果期间 advance 重入"不会重复弹窗（重入守卫）。
     * 但若进程在系统框弹出期间被杀（EMUI 激进回收 / 开发者选项"不保留活动" / 极低内存），
     * 重入后磁盘里的 `requestedOnce` 仍含这些键，而授权其实从未完成——
     * advance 据此跳过这些步骤，导致非 DO 设备上的运行时权限（定位/相机/麦克风/通知等）、
     * 或 VPN 准备、或录屏令牌**静默缺失、且不再自动请求**。
     *
     * ## 判断口径
     * 仅当"对应授权当前真实仍缺失"时才清：已授予则保留（上次确实办成了，不该重弹）；
     * 仍缺失说明上一次只是被进程死亡打断，应允许本会话重新走一遍。
     * 本机确实无法授予的项（如家长主动拒绝运行时权限）会在 advance 内走 denied/skipped 降级，
     * 不会无限弹窗；且本对账只在 loadProgress（重入）时执行一次，实时会话不受影响。
     */
    private fun reconcileInterruptedRequests() {
        if (KEY_RUNTIME in requestedOnce && permissionGranter.missingRuntimePermissions().isNotEmpty()) {
            requestedOnce -= KEY_RUNTIME
            Logger.i(TAG) { "reconcile: runtime permission still missing after restart, re-enabling request" }
        }
        if (KEY_VPN in requestedOnce && KEY_VPN !in skippedKeys && !webGuardVpn.isPrepared()) {
            requestedOnce -= KEY_VPN
            Logger.i(TAG) { "reconcile: vpn still not prepared after restart, re-enabling request" }
        }
        if (KEY_CAPTURE in requestedOnce && KEY_CAPTURE !in skippedKeys && needsCapture() && !ScreenCaptureSession.hasToken()) {
            requestedOnce -= KEY_CAPTURE
            Logger.i(TAG) { "reconcile: capture token still missing after restart, re-enabling request" }
        }
    }

    /**
     * 落盘当前已办进度：每次状态变更后调用，进程死亡后可恢复，不再从头跑。
     *
     * 必须同步 `commit()`（R29）：这份进度的**唯一存在意义**就是跨进程被杀续跑，
     * 而 `apply()` 只保证内存即时、磁盘异步——家长刚从系统设置页回来，
     * 进程恰在刷盘前被 EMUI / 「不保留活动」/ low-memory killer 回收，
     * 冷启动读到的还是上一次刷盘的旧集合，刚办完的那一趟被当成"没办过"，
     * 于是又被带回已开过的设置页（现场表现就是"授权页反复提示"）。
     * 集合只有几十个短字符串，同步写盘开销可忽略，与项目「关键状态一律 commit()」红线一致。
     */
    @SuppressLint("ApplySharedPref")
    private fun saveProgress() {
        runCatching {
            val set = mutableSetOf<String>().apply {
                skippedKeys.forEach { add("$PREFIX_SKIP$it") }
                confirmedKeys.forEach { add("$PREFIX_CONFIRM$it") }
                attemptedKeys.forEach { add("$PREFIX_ATTEMPT$it") }
                requestedOnce.forEach { add("$PREFIX_REQUEST$it") }
            }
            progressPrefs.edit().putStringSet(KEY_PROGRESS_SET, set).commit()
        }.onFailure { Logger.w(TAG) { "save consent progress failed: ${it.message}" } }
    }

    /** 授权正式完成后清除临时进度：断点续跑只服务"进行中"，完成态不应残留 started 干扰后续入口分流。 */
    @SuppressLint("ApplySharedPref")
    private fun clearProgress() {
        runCatching { progressPrefs.edit().clear().commit() }
            .onFailure { Logger.w(TAG) { "clear consent progress failed: ${it.message}" } }
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
            // 授权目录是唯一事实源：新增一项权限只需改 SpecialPermission 枚举，
            // 装机清单、版本/厂商适用判定、跳转链、掉失自检在这里一次性全部生效。
            // applicable() 会把本机不适用的项（低版本精确闹钟 / 原生 Android 的厂商开关 /
            // DO 下冗余的安装未知应用）剔掉，避免给家长看到一堆"本机根本没有"的行。
            SpecialPermission.applicable(app, admin.hasOwnerPrivileges()).forEach { perm ->
                add(
                    ConsentItem(
                        key = perm.key,
                        title = perm.label,
                        impact = perm.impact + cleanupShieldExtraFor(perm),
                        source = ConsentItem.Source.MANUAL_SETTING,
                        optional = perm.optional,
                        // 判定顺序要紧：可检测项**先问真实状态**再谈流程标记——
                        // 一项曾被"跳过/待确认"收口、家长后来又去把它开成了时，
                        // 清单必须如实升级为已完成；反过来先判 skipped 会让它永远
                        // 显示"已跳过"，家长会以为补开没生效（真机已复现过）。
                        // 不可检测项 isGranted 恒 false，不受影响。
                        state = when {
                            perm.detectable && perm.isGranted(app) -> ConsentItem.State.DONE
                            perm.key in skippedKeys -> ConsentItem.State.SKIPPED
                            perm.key in confirmedKeys -> ConsentItem.State.CONFIRMED
                            perm.key in manualKeys -> ConsentItem.State.NEED_MANUAL
                            else -> ConsentItem.State.DONE
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

    /**
     * 给「后台运行」条目拼接厂商防清理步骤（2026-11-17 EMUI 一键清掉无障碍事故）。
     *
     * ROM 的清理白名单没有统一系统页面（华为是最近任务锁手势 + 启动管理），
     * 装机这一趟必须把具体步骤讲给家长，否则清单上只有一句泛泛的省电提示，
     * 家长做完「去开启」还是会被一键清理打回原形。其他条目不拼接，保持原样。
     */
    private fun cleanupShieldExtraFor(perm: SpecialPermission): String {
        if (perm != SpecialPermission.OEM_BACKGROUND) return ""
        return RomSettings.cleanupShieldHint(RomSettings.vendorOfDevice())
            ?.let { "\n$it" }
            .orEmpty()
    }

    /**
     * 统一的「完成判定」接线，按权限类型分三档：
     * - 可检测项 → 盯真实状态，查到已授予即接回（悬浮窗 / 无障碍 / 使用情况等）；
     * - 电池优化 → 盯「已授予 或 已从设置页返回」：厂商省电白名单与官方查询 API
     *   是两套互不联通的机制（华为受保护应用上 isIgnoringBatteryOptimizations 恒 false），
     *   只盯状态会把在厂商页完成加白的家长晾在设置页直到预算耗尽；
     * - 不可检测项（厂商私有开关）→ 盯"页面返回"，绝不谎报已开启。
     *
     * 抽成一处而不是在 [advance] / [openAgain] 各写一遍：两条路若判定口径不一致，
     * 表现就是"从清单点进去能自动跳，底部按钮点进去却卡住"，回归测试极难发现。
     */
    private fun watchSettingsStep(perm: SpecialPermission) {
        when {
            perm.detectable && perm == SpecialPermission.BATTERY_OPTIMIZATION ->
                consentReturn.watchGrantedOrReturn({ perm.isGranted(app) }, perm.label)
            perm.detectable ->
                consentReturn.watch({ perm.isGranted(app) }, perm.label)
            else ->
                consentReturn.watchReturn(perm.label)
        }
    }

    /**
     * 仍待家长去系统设置页处理的项（已按 SpecialPermission 声明顺序排列）。
     *
     * 剔除两类：家长明确跳过的、以及已带去过但无状态可查的厂商项（[confirmedKeys]）。
     * 后者若不剔除，`isGranted` 恒 false 会让流水线永远停在同一项上。
     */
    private fun missingManual(): List<SpecialPermission> =
        SpecialPermission.missing(app, admin.hasOwnerPrivileges())
            .filter { it.key !in skippedKeys && it.key !in confirmedKeys }

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

    /**
     * VPN 确认页 Intent 的可解析校验。
     *
     * 设备管理员、系统设置页都有 resolveActivity 兜底，唯独 `VpnService.prepare()`
     * 返回的 Intent 过去直接硬 launch——个别定制 ROM 上会抛 ActivityNotFoundException，
     * 授权页因此弹出一段英文异常。这里与其余跳转同口径：不可解析即视为不可用，
     * 由调用方如实记为跳过并给中文指引，而不是让 launch 崩到界面上。
     */
    private fun prepareVpnIntentOrNull(): Intent? {
        val intent = webGuardVpn.prepareIntent() ?: return null
        if (!resolvable(intent)) {
            Logger.w(TAG) { "vpn prepare intent unresolvable on this ROM" }
            return null
        }
        return intent
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

        /** 授权进度持久化文件名（独立 SharedPreferences，不污染 AuthRepository）。 */
        private const val PROGRESS_PREFS = "padguard_consent_progress"
        private const val KEY_PROGRESS_SET = "progress_set"
        private const val PREFIX_SKIP = "s:"
        private const val PREFIX_CONFIRM = "c:"
        private const val PREFIX_ATTEMPT = "a:"
        private const val PREFIX_REQUEST = "r:"
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

        /**
         * 同一返回事件的去抖窗口：ActivityResult 回调与其后的 onResume 相隔通常 <100ms，
         * 800ms 足以吞掉尾随信号；家长真实的下一次"进设置页→返回"必然远大于此。
         */
        private const val RETURN_DEBOUNCE_MS = 800L

        /**
         * 卡住重试时前台轮询的间隔（毫秒）。
         * 500ms 一拍会把它叠加进"开完回页面 → 认到已授予"的感知延迟里，压到 250ms。
         */
        private const val STUCK_POLL_MS = 250L
        /** 卡住重试时前台轮询的次数上限（STUCK_POLL_MS × 该值 ≈ 5s 复检窗口）。 */
        private const val STUCK_POLL_COUNT = 20

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

/**
 * 自动接续的"下一项"决策（纯函数，便于单测锁定顺序，见 [ConsentAdvanceTest]）。
 *
 * [manual] 已按 [SpecialPermission] 声明顺序（即枚举 [SpecialPermission.ordinal] 顺序）排列，
 * 代表"当前仍待家长去系统页手动开启"的项。本函数在其中挑出"紧接 [cursorKey] 之后"的那一项，
 * 让流水线顺着清单顺序逐项推进，而不是每次都从第一项重来（那样家长会看到"开完第 4 项被拽回第 1 项"的乱跳）。
 *
 * 用 [SpecialPermission.key] 而非 ordinal 做游标比对：游标可能是已办妥、从而不在 [manual]
 * 里的项，此时按其 ordinal 顺延到下一个仍缺失的项；游标为 null（首轮）则取首项。
 * 回退规则：若 [cursorKey] 之后已无更多项（或游标根本不在列表里），返回 [manual] 首项，
 * 保证流水线永远能落到某个真缺失项上，不会空转或越界。
 */
internal fun nextManualItemAfter(
    manual: List<SpecialPermission>,
    cursorKey: String?
): SpecialPermission? {
    if (manual.isEmpty()) return null
    val cursor = manual.firstOrNull { it.key == cursorKey }?.ordinal
        ?: SpecialPermission.fromKey(cursorKey.orEmpty())?.ordinal ?: -1
    return manual.firstOrNull { it.ordinal > cursor } ?: manual.first()
}
