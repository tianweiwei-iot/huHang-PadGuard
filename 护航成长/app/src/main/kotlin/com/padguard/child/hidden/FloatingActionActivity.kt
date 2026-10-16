package com.padguard.child.hidden

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.padguard.child.MainActivity
import com.padguard.child.guard.GuardPasswordManager
import com.padguard.child.ui.theme.PadGuardTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 家长验证入口（悬浮卡的唯一触点）。
 *
 * ## 它解决什么
 * 应用隐藏后，孩子看不到也打不开管理控制台；这张悬浮卡是孩子端唯一"外露"的入口，因此必须被家长密码保护。
 * 点按悬浮卡 → 本透明 Activity → 输对家长操作密码后才能：
 *   - **进入管理控制台**：[AppHider.revealManagementUi] 重新点亮图标并打开 [MainActivity]（带 EXTRA_REVEAL，
 *     使 MainActivity 在销毁时自动重新隐藏，避免图标长期暴露给孩子）；
 *   - **退出并卸载**：[ExitController] 先撤销设备管理员再拉起系统卸载页。
 *
 * ## 没有密码怎么办
 * 若家长端尚未下发操作密码（[GuardPasswordManager.isEnabledNow] == false），两类敏感操作都无法验证，
 * 此时本页只给提示，不放行——宁可让家长先去家长端设置密码，也不留无锁后门。
 */
@AndroidEntryPoint
class FloatingActionActivity : ComponentActivity() {

    @Inject lateinit var guard: GuardPasswordManager

    @Inject lateinit var appHider: AppHider

    @Inject lateinit var exitController: ExitController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PadGuardTheme {
                ParentGateScreen(
                    guard = guard,
                    onEnterManagement = {
                        appHider.revealManagementUi()
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                .putExtra(MainActivity.EXTRA_REVEAL, true)
                        )
                        finish()
                    },
                    onExit = { exitController.requestUninstall(this) },
                    onCancel = { finish() }
                )
            }
        }
    }
}

@Composable
private fun ParentGateScreen(
    guard: GuardPasswordManager,
    onEnterManagement: () -> Unit,
    onExit: () -> Unit,
    onCancel: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var unlocked by remember { mutableStateOf(false) }
    val enabled = remember { guard.isEnabledNow() }

    if (!unlocked) {
        AlertDialog(
            onDismissRequest = onCancel,
            confirmButton = {
                TextButton(onClick = {
                    if (!enabled) {
                        error = "未设置操作密码，请先在家长端设置操作密码"
                        return@TextButton
                    }
                    scope.launch {
                        val result = guard.verifyWithLimit(password)
                        if (result.ok) {
                            unlocked = true
                            error = null
                        } else {
                            error = when {
                                result.lockedForMs > 0 -> "密码错误次数过多，已锁定，请稍后再试"
                                else -> "密码错误，还可尝试 ${result.attemptsLeft} 次"
                            }
                        }
                    }
                }) { Text("验证") }
            },
            dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
            title = { Text("家长验证") },
            text = {
                Column {
                    if (!enabled) {
                        Text(
                            "操作密码未设置，无法执行此操作。请先在家长端设置操作密码后再试。",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text("请输入家长操作密码以进入管理或退出应用。", fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("操作密码") },
                        singleLine = true,
                        enabled = enabled,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            }
        )
    } else {
        AlertDialog(
            onDismissRequest = onCancel,
            confirmButton = { },
            title = { Text("家长操作") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("验证通过，请选择操作：", fontSize = 13.sp)
                    Button(
                        onClick = onEnterManagement,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("进入管理控制台") }
                    Button(
                        onClick = onExit,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("退出并卸载") }
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("取消") }
                }
            }
        )
    }
}
