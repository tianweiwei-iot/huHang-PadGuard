package com.padguard.child.guard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
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
 * **打开应用**的门禁不在这里，而是 [AppAccessGateActivity]（盖在应用之上的透明浮层）
 * 与 [com.padguard.child.KidHomeActivity] 的就地对话框，它们走
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

    override fun onResume() {
        super.onResume()
        visible = true
    }

    override fun onPause() {
        super.onPause()
        visible = false
    }

    override fun onDestroy() {
        visible = false
        super.onDestroy()
    }

    companion object {
        /**
         * 本密码输入页是否处于前台（供锁屏看门狗判定，**不是** Composable 里那个动画用的局部变量）。
         *
         * ## 为什么要暴露这个标记
         * 锁屏看门狗原先用「前台包名 == 本包」判断"锁屏还在、无需重拉"。这个口径太宽：
         * 本包还有儿童桌面 [com.padguard.child.KidHomeActivity] 等页面，孩子按 HOME 回到
         * 自家桌面时包名同样是自己，看门狗于是**跳过重拉**，锁屏形同虚设。
         * 现在只有"锁屏主体"或"本密码页"在前台才算没逃逸；本页在前台时必须放过，
         * 否则家长正输着密码就被重拉的锁屏盖掉，永远解不开。
         */
        @Volatile
        var visible: Boolean = false
            private set
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
    var verifying by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(manager.isLockedOutNow()) }
    var visible by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    fun submit() {
        if (verifying || locked || password.isEmpty()) return
        verifying = true
        scope.launch {
            val r = manager.verifyWithLimit(password)
            verifying = false
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
                    password = ""
                }
            }
        }
    }

    PadGuardTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 与应用门禁卡片同一视觉家族：圆角卡片 + 圆形图标徽章，居中不顶满
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .wrapContentHeight()
                        .widthIn(max = 440.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        GateIconBadge(Icons.Outlined.AdminPanelSettings)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "设备操作已上锁",
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "要安装/卸载应用或修改系统设置，请输入家长密码。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(20.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it; error = null },
                            label = { Text("家长密码") },
                            singleLine = true,
                            enabled = !locked && !verifying,
                            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            trailingIcon = {
                                IconButton(onClick = { visible = !visible }) {
                                    Icon(
                                        imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = if (visible) "隐藏密码" else "显示密码",
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    )
                                }
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { submit() }),
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                focusedContainerColor = MaterialTheme.colorScheme.surface
                            ),
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                        )
                        if (error != null) {
                            Spacer(Modifier.height(10.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    Icons.Outlined.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    " $error",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = { submit() },
                            enabled = !locked && !verifying,
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) {
                            if (verifying) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text(if (locked) "已锁定" else "解锁（10 分钟）")
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = onClose,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("取消", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        }
    }
}
