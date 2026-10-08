package com.padguard.child.guard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.padguard.child.ui.theme.PadGuardTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 操作密码解锁界面（覆盖层，复用锁屏主题）。
 *
 * 家长在此输入密码 → 校验通过 → [GuardPasswordManager.unlock] 开启允许窗口，
 * 之后一段时间内允许安装/卸载/改设置（由 [OperationGuardAccessibilityService] 放行）。
 * 若已处于解锁窗口，则显示「重新锁定」入口。
 *
 * 注意：这是「家长」操作入口，孩子不知道密码，因此打开设置/安装被拦截时，
 * 只有当家长能在此输对密码才能放行。
 */
@AndroidEntryPoint
class GuardUnlockActivity : ComponentActivity() {

    @Inject
    lateinit var manager: GuardPasswordManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 未启用密码时本界面无意义，直接结束。
        if (!manager.isEnabledNow()) {
            finish()
            return
        }
        // 可选：孩子从桌面点开「受管控且需密码」的应用时传入，
        // 解锁成功后自动打开该应用（避免"输完密码还要再点一次"）。
        val launchPackage = intent.getStringExtra(EXTRA_LAUNCH_PACKAGE)
        setContent {
            GuardUnlockScreen(
                manager = manager,
                launchPackage = launchPackage,
                onClose = { finish() }
            )
        }
    }

    companion object {
        /** 解锁成功后要自动打开的目标应用包名（孩子桌面点开受管控应用时传入） */
        const val EXTRA_LAUNCH_PACKAGE = "extra_launch_package"
    }
}

@Composable
private fun GuardUnlockScreen(
    manager: GuardPasswordManager,
    launchPackage: String?,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var locked by remember { mutableStateOf(manager.isLockedOutNow()) }

    // 文案随场景切换：被管控应用入口 → 打开校验；系统/安装拦截入口 → 操作解锁。
    val title = if (launchPackage != null) "打开应用需要验证" else "设备操作已上锁"
    val desc = if (launchPackage != null) {
        "请输入管理密码以打开该应用。"
    } else {
        "要安装/卸载应用或修改系统设置，请输入家长密码。"
    }

    PadGuardTheme {
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                desc,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; error = null },
                label = { Text("家长密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error!!, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    scope.launch {
                        val r = manager.verifyWithLimit(password)
                        if (r.ok) {
                            manager.unlock()
                            // 孩子从桌面点开受管控应用时，解锁后自动打开目标应用
                            launchPackage?.let { pkg ->
                                context.packageManager.getLaunchIntentForPackage(pkg)
                                    ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    ?.let { context.startActivity(it) }
                            }
                            onClose()
                        } else {
                            // 如实告知剩余次数：既避免把孩子引到"无限重试"，
                            // 也让家长一眼看出是不是孩子在乱试。
                            if (r.lockedForMs > 0) {
                                locked = true
                                val minutes = (r.lockedForMs / 60_000L) + 1
                                error = "尝试次数过多，请 ${minutes} 分钟后再试"
                            } else {
                                error = "密码错误，还可尝试 ${r.attemptsLeft} 次"
                            }
                        }
                    }
                },
                enabled = !locked,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text(if (locked) "已锁定" else "解锁（10 分钟）")
            }
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.TextButton(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("取消")
            }
        }
    }
}
