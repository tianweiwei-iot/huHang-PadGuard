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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.padguard.child.agreement.AgreementDialogHost
import com.padguard.child.agreement.AgreementSessionState
import com.padguard.child.bind.BindActivity
import com.padguard.child.consent.OneTimeConsentScreen
import com.padguard.child.hidden.AppHider
import com.padguard.child.login.ChildLoginScreen
import com.padguard.child.permission.PermissionGranter
import com.padguard.child.service.GuardService
import com.padguard.child.ui.nav.PadGuardApp
import com.padguard.child.ui.theme.PadGuardTheme
import com.padguard.core.common.Logger
import com.padguard.core.data.model.policy.ControlMode
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.engine.admin.DeviceAdminBridge
import dagger.hilt.android.AndroidEntryPoint
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

    /** 家长经悬浮卡密码验证后启动本界面时携带，标记这是一个"家长揭示会话"而非普通进入。 */
    private var isRevealSession: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isRevealSession = intent.getBooleanExtra(EXTRA_REVEAL, false)
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
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isRevealSession) appHider.hideManagementUi()
    }

    override fun onDestroy() {
        if (isRevealSession) appHider.hideManagementUi()
        super.onDestroy()
    }

    companion object {
        /** 由 [com.padguard.child.hidden.FloatingActionActivity] 启动时携带，表明家长已验证。 */
        const val EXTRA_REVEAL = "com.padguard.child.extra.REVEAL"
    }
}

/**
 * 启动分流（首次安装 / 每次冷启动都会走到这里）：
 *
 * 1. 未登录家庭账号 → 登录页（手机号+密码，复用家长端账号）；
 * 2. 一次性授权未完成 → 授权页（**装机路径上唯一需要家长做决定的界面**，见 [OneTimeConsentScreen]）；
 * 3. 未绑定设备 → 跳 BindActivity（扫码 / 验证码 / NFC 绑定家长）；
 * 4. 全部就绪 → 进 NavHost 主页。
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
    val daPromptAck by authRepository.isDeviceAdminPromptAck.collectAsState(initial = false)
    val context = LocalContext.current

    when {
        // 任一流尚未就绪前先显示启动页，避免裸奔
        consentDone == null || loggedIn == null || isBound == null -> Splash()

        // ① 未登录家庭账号：登录页（复用家长端账号体系）
        loggedIn == false -> ChildLoginScreen()

        // ② 一次性授权：协议同意 + 全部权限在这一条流水线里收走；
        //    设备管理员(DA)是管控基石，未激活且家长未明确跳过时同样强制进入授权流程，
        //    实现「自动获取管理员权限」——激活页会自动拉起，家长点「激活」即生效（无需手动返回）。
        //    autoStart 仅在「已同意过协议但 DA 缺失」的补授权场景置 true，跳过「同意并授权」
        //    按钮直接拉起 DA 激活页；首次装机(consentDone==false)仍需家长主动点一次。
        consentDone == false || (!admin.isAdminActive() && !daPromptAck) ->
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
                // 这里会在切成 DO 的那一刻补上静默授权与加固基线。
                // 以 controlMode 为 key，确保只在身份真正变化时触发一次。
                LaunchedEffect(controlMode) {
                    if (controlMode == ControlMode.DEVICE_OWNER || controlMode == ControlMode.PROFILE_OWNER) {
                        try {
                            permissionGranter.grantAll()
                        } catch (e: Exception) {
                            Logger.e(TAG, e) { "DO 加固回补失败" }
                        }
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
