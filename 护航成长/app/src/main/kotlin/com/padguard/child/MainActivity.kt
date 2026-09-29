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
import com.padguard.child.agreement.AgreementDialogHost
import com.padguard.child.bind.BindActivity
import com.padguard.child.capture.CaptureConsent
import com.padguard.child.capture.CapturePermissionActivity
import com.padguard.child.login.ChildLoginScreen
import com.padguard.child.permission.PermissionOnboardingScreen
import com.padguard.child.service.GuardService
import com.padguard.child.ui.nav.PadGuardApp
import com.padguard.child.ui.theme.PadGuardTheme
import com.padguard.core.data.repository.AuthRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var authRepository: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PadGuardTheme {
                EntryRouter(authRepository = authRepository)
            }
        }
    }
}

/**
 * 启动分流（首次安装 / 每次冷启动都会走到这里）：
 *
 * 1. 权限未完成 → 权限一键引导页（初次打开一次性授予，完成后持久化）；
 * 2. 未登录家庭账号 → 登录页（手机号+密码，复用家长端账号）；
 * 3. 未绑定设备 → 跳 BindActivity（扫码 / 验证码 / NFC 绑定家长）；
 * 4. 全部就绪 → 进 NavHost 主页；未同意协议则叠加授权弹窗。
 *
 * 各态由 DataStore Flow 驱动，登录 / 权限完成会触发自动重组推进到下一态，
 * 因此各子页无需自行导航。单独抽 Composable 是因为 LaunchedEffect 必须在 Composition 上下文里跑。
 */
@Composable
private fun EntryRouter(authRepository: AuthRepository) {
    val permissionsDone by authRepository.isPermissionsDone.collectAsState(initial = null)
    val loggedIn by authRepository.isChildLoggedIn.collectAsState(initial = null)
    val isBound by authRepository.isBound.collectAsState(initial = null)
    val agreed by authRepository.isAgreementAccepted.collectAsState(initial = null)
    val context = LocalContext.current

    when {
        // 任一流尚未就绪前先显示启动页，避免裸奔
        permissionsDone == null || loggedIn == null || isBound == null -> Splash()

        // ① 初次打开：一次性授予权限（后续登录因 PERMISSIONS_DONE 持久化而跳过此步）
        permissionsDone == false -> PermissionOnboardingScreen()

        // ② 未登录家庭账号：登录页（复用家长端账号体系）
        loggedIn == false -> ChildLoginScreen()

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

        // ④ 全部就绪：主页常驻；未同意协议则叠加授权弹窗
        else -> {
            PadGuardApp()

            // 已登录且已绑定：确保守护服务在跑。
            // 守护服务此前只在开机 / 设备管理员启用时启动，一旦被系统回收或用户在
            // 「最近任务」划掉，心跳与 MQTT 上行就断，家长端看到设备一直 OFFLINE。
            // startForegroundService 对已运行的服务只会再投递一次 intent（幂等），
            // 因此每次进主页兜底拉一次是安全的。
            LaunchedEffect(Unit) {
                GuardService.start(context, "mainEntry")
            }

            // 装机就绪后预置一次屏幕采集授权。
            // MediaProjection 必须由用户在系统弹窗确认一次，这里提前拿掉，
            // 家长后续下发"实时看屏/录屏"时不需孩子再点确认、也不会有突兀弹窗。
            LaunchedEffect(Unit) {
                if (CaptureConsent.shouldRequest(context)) {
                    runCatching { context.startActivity(CapturePermissionActivity.intent(context)) }
                }
            }

            if (agreed != true) AgreementDialogHost()
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
