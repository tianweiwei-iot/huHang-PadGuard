package com.padguard.parent.presentation.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.padguard.parent.presentation.viewmodel.PasswordChangeState

/**
 * 修改登录密码弹窗。
 *
 * 为什么这个入口必须存在：账号的初始密码、以及运营在后台「重置密码」生成的，
 * 都是服务端随机强口令。口令文件与后台弹窗都写着"请登录后尽快修改"，
 * 但此前**没有任何修改入口** —— 家长只能一直对着截图里的随机串输入，
 * 每次忘记都再找一次客服重置。这条链路的最后一环就是这里。
 *
 * 交互上的三个取舍：
 * - 旧密码必填：令牌不等于身份（二手平板、借用他人手机都可能留着有效 token）；
 * - 新密码输两遍：改密码失败最常见的成因是打错字，而打错字后家长会以为自己记错了密码；
 * - 错误就地显示在输入框下方、不关弹窗：错误文案（"原密码不正确"）指向的正是弹窗里的字段，
 *   关掉再重开会丢掉已填内容，等于逼用户重敲两遍。
 *
 * 成功后由 [PasswordChangeState.done] 触发关闭：此时服务端已把本机令牌换新并落盘，
 * 用户不需要重新登录 —— 这一点要在提示里说清楚，否则会怀疑"改完是不是掉线了"。
 */
@Composable
fun ChangePasswordDialog(
    state: PasswordChangeState,
    onSubmit: (oldPassword: String, newPassword: String, confirmPassword: String) -> Unit,
    onDismiss: () -> Unit
) {
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    // 成功后自动关闭：留着一个"已完成"的弹窗会让用户以为还要再点一次确定。
    LaunchedEffect(state.done) {
        if (state.done) onDismiss()
    }

    val busy = state.submitting
    val transformation =
        if (visible) VisualTransformation.None else PasswordVisualTransformation()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("修改登录密码") },
        text = {
            // 键盘弹起 + 三个输入框在小屏上会挤掉按钮，必须可滚动
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "建议把运营商/客服给的初始密码改掉，改成只有你自己知道的密码。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                OutlinedTextField(
                    value = oldPassword,
                    onValueChange = { oldPassword = it },
                    label = { Text("当前密码") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = transformation,
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (visible) "隐藏密码" else "显示密码"
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    label = { Text("新密码") },
                    placeholder = { Text("至少 6 位") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = transformation,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = { Text("确认新密码") },
                    placeholder = { Text("再输一次，防止打错") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = transformation,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                if (state.error != null) {
                    Text(
                        text = state.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Text(
                    "修改成功后，其他设备上的登录状态会失效，本机保持登录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = { onSubmit(oldPassword, newPassword, confirmPassword) }
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp).padding(end = 6.dp),
                        strokeWidth = 2.dp
                    )
                }
                Text(if (busy) "提交中…" else "确认修改")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        }
    )
}
