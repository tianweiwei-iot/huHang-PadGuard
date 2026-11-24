package com.padguard.child

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.padguard.child.ConsoleState
import com.padguard.child.agreement.AgreementDialogHost
import com.padguard.child.agreement.AgreementSessionState
import com.padguard.child.bind.BindActivity
import com.padguard.child.consent.OneTimeConsentScreen
import com.padguard.child.hidden.AppHider
import com.padguard.child.login.ChildLoginScreen
import com.padguard.child.permission.DoProvisioningScreen
import com.padguard.child.permission.ManagementModeChoiceScreen
import com.padguard.child.permission.PermissionGranter
import com.padguard.child.service.GuardService
import com.padguard.child.ui.nav.PadGuardApp
import com.padguard.child.ui.theme.PadGuardTheme
import com.padguard.core.common.Logger
import com.padguard.core.data.model.policy.ControlMode
import com.padguard.core.data.model.policy.ManagementMode
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.engine.admin.DeviceAdminBridge
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import javax.inject.Inject

private const val TAG = "EntryRouter"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var authRepository: AuthRepository

    @Inject
    lateinit var admin: DeviceAdminBridge

    @Inject
    lateinit var permissionGranter: PermissionGranter

    @Inject
    lateinit var agreementSessionState: AgreementSessionState

    @Inject
    lateinit var appHider: AppHider

    /**
     * 是否由**已鉴权路径**（悬浮卡密码验证 / 服务端 REVEAL_MANAGEMENT_UI 指令）进入。
     *
     * 绝不能信任 Intent 里的布尔 extra：MainActivity 虽已 non-exported，但桌面 alias 是
     * 导出的，外部 App 可显式启动 alias 并透传 EXTRA_REVEAL=true。现在 extra 只携带
     * 进程内一次性令牌，由 [newRevealToken] 在鉴权通过后签发、[consumeRevealToken] 消费即失效。
     */
    private var isRevealSession: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isRevealSession = consumeRevealToken(intent.getStringExtra(EXTRA_REVEAL))
        enableEdgeToEdge()
        setContent {
            PadGuardTheme {
                EntryRouter(
                    authRepository = authRepository,
                    admin = admin,
                    permissionGranter = permissionGranter,
                    agreementSessionState = agreementSessionState,
                    isRevealSession = isRevealSession
                )
            }
        }
    }

    /**
     * 家长揭示会话结束时重新隐藏管理控制台。
     *
     * 两种触发：
     * - [onUserLeaveHint]：家长按 HOME / 多任务键离开，立即收起图标，避免图标长期暴露给孩子；
     * - [onDestroy]：正常退出管理（覆盖前者的兜底）。
     * 非揭示会话（正常装机、隐藏态被 finish）绝不触发，否则会把装机的孩子端入口一并藏掉。
     */
    /**
     * singleTop 复用已有实例时不会走 onCreate：远程揭示指令若落在控制台已打开的时刻，
     * 必须在这里刷新会话态，否则令牌被消费掉却仍按旧状态渲染（可能是孩子隐藏态）。
     */
    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        intent = newIntent
        val revealed = consumeRevealToken(newIntent.getStringExtra(EXTRA_REVEAL))
        if (revealed != isRevealSession) {
            isRevealSession = revealed
            recreate()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isRevealSession) appHider.hideManagementUi()
    }

    override fun onDestroy() {
        if (isRevealSession) appHider.hideManagementUi()
        super.onDestroy()
    }

    companion object {
        /**
         * 携带一次性揭示令牌（**不是布尔值**）。签发方只有两处：
         * [com.padguard.child.hidden.FloatingActionActivity] 密码校验通过后、
         * [com.padguard.child.service.GuardService] 收到服务端家长鉴权的 REVEAL_MANAGEMENT_UI 后。
         */
        const val EXTRA_REVEAL = "com.padguard.child.extra.REVEAL"

        @Volatile
        private var pendingRevealToken: String? = null

        /** 鉴权通过后签发；只保存在进程内存，重启即失效，外部 App 无法伪造。 */
        @JvmStatic
        fun newRevealToken(): String = UUID.randomUUID().toString().also { pendingRevealToken = it }

        /** 消费即清除：同一令牌即使被重放，第二次也返回 false。 */
        @JvmStatic
        private fun consumeRevealToken(token: String?): Boolean {
            if (token.isNullOrBlank()) return false
            val expected = pendingRevealToken ?: return false
            pendingRevealToken = null
            return expected == token
        }
    }
}

/**
 * 启动分流（首次安装 / 每次冷启动都会走到这里）：
 *
 * 0. 装机首启尚未选择管控模式 → 二选一页（[ManagementModeChoiceScreen]：硬管控走 DO 预置，软管控走三件套）；
 * 1. 未登录家庭账号 → 登录页（手机号+密码，复用家长端账号）；
 * 2. 一次性授权未完成 → 授权页（见 [OneTimeConsentScreen]）；
 * 3. 未绑定设备 → 跳 BindActivity（扫码 / 验证码 / NFC 绑定家长）；
 * 4. 全部就绪 → 进 NavHost 主页。
 *
 * ## 硬 / 软两档并行
 * 第 0 步的选择是**用户意图**（ManagementMode），与设备实际权限等级（ControlMode）解耦：
 * 选硬管控才进入 DO 预置引导；选软管控直接跳过，不再被发布包的强制 DO 首步拦截。
 * 两档授权与配置各自保留，后续在「我的」页可随时切换，互不清除。
 *
 * ## 为什么授权排在绑定之前
 * 绑定要扫家长端的二维码，而扫码需要相机权限。若授权排在绑定之后，
 * 家长就会在"最后一步"被系统相机权限框打断——一次授权的承诺当场作废。
 * 把授权放在绑定之前，扫码时权限已就位，绑定这一步就只剩对准摄像头。
 *
 * ## 这里不再有任何第二道门禁
 * 历史上在主页之前还叠了「设备管理员/设备所有者门禁」、主页之上还叠了
 * 「协议同意弹窗」与「屏幕采集授权」，家长要连过四五道关才能用上产品。
 * 现在这些全部收敛进第 2 步那条流水线：
 * - 设备管理员由流水线在系统确认页里争取一次（没拿到也不卡住，App 内仍有补开入口）；
 * - 协议同意在家长点「同意并授权」的那一刻落库；
 * - 屏幕采集授权由流水线在第一时刻拿到。
 * 因此主页不会再叠加任何授权 UI；这里保留的 [AgreementDialogHost] 只在
 * **协议正文升版**时才会出现（需要重新征求意见），正常装机不会触发。
 *
 * 各态由 DataStore Flow 驱动，完成即自动重组推进到下一态，子页无需自行导航。
 */
@Composable
private fun EntryRouter(
    authRepository: AuthRepository,
    admin: DeviceAdminBridge,
    permissionGranter: PermissionGranter,
    agreementSessionState: AgreementSessionState,
    isRevealSession: Boolean
) {
    val consentDone by authRepository.isConsentDone.collectAsState(initial = null)
    val loggedIn by authRepository.isChildLoggedIn.collectAsState(initial = null)
    val isBound by authRepository.isBound.collectAsState(initial = null)
    val agreed by authRepository.isAgreementAccepted.collectAsState(initial = null)
    val controlMode by admin.controlMode.collectAsStateWithLifecycle()
    // 家长是否已明确跳过设备管理员引导：为 true 时不再因「DA 未激活」强制弹回授权页
    // 这四条与上面三条一样来自 DataStore，首帧 initial 必须是 null（未知），不能用 false。
    // 旧写法只有 consentDone/loggedIn/isBound 进了 Splash gate：那三条到达后本帧就会走
    // 入口分流，而 doPromptAck=false 会误弹 DO 预置页、reauthRequired=false 会先走隐藏态
    // 分支执行 finish() 把 Activity 关掉 —— 真实值随后到达也来不及重组（授权页一闪就没）。
    val daPromptAck by authRepository.isDeviceAdminPromptAck.collectAsState(initial = null)
    // 装机授权完成后某项被关掉，需家长重新勾选（见 AuthorizationKeeper）
    val reauthRequired by authRepository.reauthRequired.collectAsState(initial = null)
    // 家长是否已显式跳过「设备所有者(DO)」预置引导：为 true 时不再因「未成为 DO」卡在强制首步
    val doPromptAck by authRepository.isDeviceOwnerPromptAck.collectAsState(initial = null)
    // 装机首启选择的管控模式：initial=null 表示"尚未读到"（Splash 等齐），
    // 读到后 mode 仍为 null 才表示"家长确实没选过"→ 弹二选一页。二者必须分开，否则首帧抢跑。
    val managementState by authRepository.managementState.collectAsState(initial = null)
    // DO 与 PO 都具备所有者级权限，都能静默授权/加固；旧写法只认 DO，
    // 导致 PO 设备选了硬管控后永远卡在预置页（明明已经具备所需权限）。
    val isDeviceOwner = controlMode == ControlMode.DEVICE_OWNER ||
        controlMode == ControlMode.PROFILE_OWNER
    // 只有家长明确选了硬管控才走 DO 预置；软管控不再被任何版本的强制首步拦截。
    val hardSelected = managementState?.mode == ManagementMode.HARD
    val context = LocalContext.current

    // 家长操作态标记：一次性授权流程（含 reauth 补授权）、登录页与揭示会话（家长控制台）在场时为真，
    // 隐藏态（孩子正常使用平板，MainActivity 已销毁）为假。供 [CaptureConsentPrompter] 判定
    // "此刻能否向设备持有者弹看屏确认框"，确保孩子使用中不被打扰（见需求：使用过程不指望孩子确认授权）。
    // 用 DisposableEffect 在场期间置真、销毁时（onDispose）复位为假——避免家长关闭控制台后
    // 残留 true 导致后续孩子正常使用设备时仍被弹确认框。
    // 登录页 / 绑定页同样是「家长在场」，注释里的口径必须落到判据上。
    val parentUiVisible = managementState?.mode == null ||
        reauthRequired == true ||
        consentDone == false ||
        loggedIn == false ||
        isBound == false ||
        (!admin.isAdminActive() && daPromptAck != true) ||
        isRevealSession
    // 实时写入：各 DataStore 流首帧取的是 collectAsState 的 initial（多为 null/false），
    // 旧实现只在 DisposableEffect(Unit) 里写一次，真实值到达后标记会永久停在首帧的错误状态，
    // 于是家长在场时 CaptureConsentPrompter 判 !isManagementVisible 直接 return，
    // 补授权 / 看屏确认框静默不弹（"点了没反应"）。
    LaunchedEffect(parentUiVisible) {
        ConsoleState.setManagementVisible(parentUiVisible)
    }
    // 离场复位仍用常量 key：拆开写，避免 parentUiVisible 变化时 onDispose 造成瞬时 false 窗口。
    DisposableEffect(Unit) {
        onDispose { ConsoleState.setManagementVisible(false) }
    }

    when {
        // 任一流尚未就绪前先显示启动页，避免裸奔。
        // 八条 Flow 都必须等齐：任何一个用 initial=false 抢跑，都会在真实值到达前
        // 作出「跳过 / 隐藏 / 弹预置页」这类不可撤销的决策（R36）。
        // managementState 用对象包住"已读到"，与"家长确实没选"区分开。
        consentDone == null || loggedIn == null || isBound == null ||
            agreed == null || daPromptAck == null || reauthRequired == null ||
            doPromptAck == null || managementState == null -> Splash()

        // 装机首启第 0 步：家长还没选过管控模式，先二选一（登录 / 授权 / 绑定之前）。
        managementState?.mode == null -> ManagementModeChoiceScreen()

        // 家长选了硬管控，但设备尚未成为 DO → 进入预置引导。
        // 选软管控时本分支恒不命中，DO 预置与三件套授权互不打扰（两档真正并行）。
        // doPromptAck 兼容老版本已显式放行过的设备，避免升级后突然被拦。
        hardSelected && !isDeviceOwner && doPromptAck != true -> DoProvisioningScreen()

        // ① 未登录家庭账号：登录页（复用家长端账号体系）
        loggedIn == false -> ChildLoginScreen()

        // ② 授权回退自愈：装机完成后若有项被关掉，自动把家长带回一次性授权引导流程重新勾选，
        //    落实"授权不会自动丢失"。autoStart=true 跳过「同意并授权」按钮直接续跑缺失项。
        reauthRequired == true -> OneTimeConsentScreen(autoStart = true)

        // ③ 一次性授权：协议同意 + 全部权限在这一条流水线里收走；
        //    设备管理员(DA)是管控基石，未激活且家长未明确跳过时同样强制进入授权流程，
        //    实现「自动获取管理员权限」——激活页会自动拉起，家长点「激活」即生效（无需手动返回）。
        //    autoStart 仅在「已同意过协议但 DA 缺失」的补授权场景置 true，跳过「同意并授权」
        //    按钮直接拉起 DA 激活页；首次装机(consentDone==false)仍需家长主动点一次。
        //
        //    ⚠️ daPromptAck 不只由"家长点跳过"写入：授权流水线**带家长去过激活页仍未激活**
        //    时也会落一次（见 OneTimeConsentViewModel.markDeviceAdminGiveUp）。
        //    少了这条，在激活页点不了「激活」的机型（ROM 阉割 / 已被其它管理员占用）上，
        //    这里会变成"每次冷启动都把家长弹回授权页"的死循环，体验上就是
        //    "设备管理员这项怎么都授权不了，还退不出去"。
        consentDone == false || (!admin.isAdminActive() && daPromptAck != true) ->
            OneTimeConsentScreen(autoStart = consentDone == true)

        // ③ 已登录但未绑定设备：跳绑定流程（扫码 / 验证码 / NFC 绑定家长）
        //
        // 这里**绝不能带 FLAG_ACTIVITY_CLEAR_TASK**：那会把 MainActivity 一起清出任务栈，
        // 绑定页 finish 之后任务栈就空了，孩子直接被丢回桌面，App 再也回不去，
        // 而家长端还显示设备在线——管控从此"看起来都正常、实际全都收不到指令"。
        // 保留 MainActivity 在栈内，绑定页 finish 即可自然回落主页。
        isBound == false -> LaunchedEffect(Unit) {
            val intent = Intent(context, BindActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        // ④ 全部就绪：主页常驻
        else -> {
            // 已绑定但不是家长揭示会话：孩子端处于「隐藏」形态，不允许向孩子展示管理控制台
            // （否则孩子可自行篡改管控）。此处直接收起 Activity，由悬浮状态卡承载基本信息展示；
            // 守护服务兜底拉起一次，确保绑定完成即进入隐藏态。
            if (!isRevealSession) {
                LaunchedEffect(Unit) {
                    GuardService.start(context, "hiddenEntry")
                    (context as? MainActivity)?.finish()
                }
            } else {
                PadGuardApp()

                // DO/PO 之后也能补齐：装机时若还不是设备所有者，
                // 这里会在切成所有者的那一刻补上静默授权与加固基线。
                // key 必须同时含 controlMode 与 hardSelected：否则家长从软管控切到硬管控、
                // 设备身份没变时不会回补；反之只看模式也会在身份变化前空转。
                LaunchedEffect(controlMode, hardSelected) {
                    val hasOwnerPrivileges = controlMode == ControlMode.DEVICE_OWNER ||
                        controlMode == ControlMode.PROFILE_OWNER
                    if (!hasOwnerPrivileges) return@LaunchedEffect
                    try {
                        if (hardSelected) {
                            // 硬管控：静默授权 + 加固基线（禁恢复出厂/安全模式/改时间等）
                            permissionGranter.grantAll()
                        } else {
                            // 软管控：只补运行时权限。grantAll 会施加硬管控专属加固基线，
                            // 绕过家长选择，且切回软档后没有对应的撤销路径。
                            permissionGranter.ensureGranted()
                        }
                    } catch (e: Exception) {
                        Logger.e(TAG, e) { "owner privileges backfill failed" }
                    }
                }

                // 已登录且已绑定：确保守护服务在跑。
                // 守护服务一旦被系统回收或用户在「最近任务」划掉，心跳与 MQTT 上行就断，
                // 家长端看到设备一直 OFFLINE。startForegroundService 对已运行的服务
                // 只会再投递一次 intent（幂等），因此每次进主页兜底拉一次是安全的。
                LaunchedEffect(Unit) {
                    GuardService.start(context, "mainEntry")
                }

                // 仅在协议正文升版时征求重新同意；正常装机已在第 ② 步同意过，不会弹。
                //
                // declined 是「本次运行期已拒绝」的会话级标记：家长点了「暂不同意」就收起弹窗、
                // 进入受限预览模式（不授予高敏权限），但**不落库**，冷启动后重新征求。
                // 少了这一个条件，弹窗在 decline 之后仍然满足挂载判定，
                // 而弹窗本身又不可点外部关闭 —— 设备会被永久卡死在协议页上。
                val declined by agreementSessionState.declined.collectAsStateWithLifecycle()
                if (agreed != true && !declined) AgreementDialogHost()
            }
        }
    }
}

@Composable
private fun Splash() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}
