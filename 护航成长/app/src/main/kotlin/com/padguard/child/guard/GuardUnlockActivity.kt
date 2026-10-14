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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.padguard.child.ui.theme.PadGuardTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * **设备操作**解锁界面（安装 / 卸载 / 改系统设置）。
 *
 * ## 职责边界（重要）
 * 本界面只管「设备操作」这一类：家长输对密码 → [GuardPasswordManager.unlock] 开启
 * 10 分钟解锁窗口 → 期间放行安装 / 卸载 / 改设置。
 *
 * **打开应用**的门禁不在这里，而是 [AppAccessGateActivity]（浮层）与
 * [com.padguard.child.KidHomeActivity] 的就地对话框，它们走
 * [GuardPasswordManager.grantAppAccess]，只放行一个包。
 *
 * 历史上这两件事曾被塞进同一个界面：结果"打开一个应用"被迫继承了本页面
 * 的全屏形态（孩子看到的像是进了管控系统），且顺带拿到 10 分钟设备操作解锁
 * （权限放大）。现已按语义拆开，两边不再共用入口。
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
        if (!manager.isEnabledNow()) {
            // 未设置「设备操作密码」：没有可比对凭据，拉起也没有意义，直接收起。
            finish()
            return
        }
        setContent {
            GuardUnlockScreen(
                manager = manager,
                onClose = { finish() }
            )
        }
    }
}

@Composable
private fun GuardUnlockScreen(
    manager: GuardPasswordManager,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var locked by remember { mutableStateOf(manager.isLockedOutNow()) }

    PadGuardTheme {
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("设备操作已上锁", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "要安装/卸载应用或修改系统设置，请输入家长密码。",
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
