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
 * 启动分流：未绑 → 跳 BindActivity；已绑 → 进 NavHost 主页。
 *
 * 单独抽 Composable 而不是在 Activity 里直接 collectAsState，
 * 是因为 LaunchedEffect 需要在 Composition 上下文里跑，
 * 否则 Hilt 注入的 repository 不会触发重组。
 *
 * 已绑定但未同意协议时，主页之上叠加 [AgreementDialogHost]（说明书 §5）：
 * 协议是权限自动授予的合规前置，必须先明示授权再启用远程管控能力。
 */
@Composable
private fun EntryRouter(authRepository: AuthRepository) {
    val isBound by authRepository.isBound.collectAsState(initial = null)
    val agreed by authRepository.isAgreementAccepted.collectAsState(initial = null)
    val context = LocalContext.current

    when {
        isBound == null -> Splash()
        isBound == false -> LaunchedEffect(Unit) {
            val intent = Intent(context, BindActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(intent)
        }
        else -> {
            // 已绑定：主页常驻；未同意协议则叠加授权弹窗
            PadGuardApp()
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
