package com.padguard.presentation.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import com.padguard.presentation.ui.theme.PadGuardColors
import com.padguard.presentation.ui.components.SoftCard
import com.padguard.presentation.viewmodel.LoginViewModel
import com.padguard.presentation.viewmodel.buildServerConfig
import com.padguard.presentation.viewmodel.parseServerConfig
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast

/**
 * 登录页 (P0 优先级)
 *
 * 功能：
 * - 手机号 + 密码登录
 * - 手机号 + 短信验证码登录（切换）
 * - 忘记密码入口
 * 
 * UI 风格：参考截图的简洁卡片式设计，蓝色主调
 */
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    viewModel: LoginViewModel = androidx.hilt.navigation.compose.hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val serverUrl by viewModel.serverUrl.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(uiState.isLoggedIn) {
        if (uiState.isLoggedIn) {
            onLoginSuccess()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Transparent
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(80.dp))

            // Logo / App 名称
            Text(
                text = "护航管控",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "家长控制端",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.height(48.dp))

            // 登录方式切换
            LoginModeToggle(
                isSmsMode = uiState.isSmsMode,
                onToggle = viewModel::toggleLoginMode
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 登录表单卡片
            SoftCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(24.dp)
                ) {
                    if (uiState.isSmsMode) {
                        SmsLoginForm(
                            phone = uiState.phone,
                            code = uiState.smsCode,
                            onPhoneChange = viewModel::updatePhone,
                            onCodeChange = viewModel::updateSmsCode,
                            onSendCode = { viewModel.sendSmsCode(uiState.phone) },
                            isCodeSending = uiState.isSendingCode,
                            countdown = uiState.countdown,
                            onLogin = { viewModel.loginWithSms() }
                        )
                    } else {
                        PasswordLoginForm(
                            phone = uiState.phone,
                            password = uiState.password,
                            onPhoneChange = viewModel::updatePhone,
                            onPasswordChange = viewModel::updatePassword,
                            onLogin = { viewModel.loginWithPassword() },
                            isLoading = uiState.isLoading
                        )
                    }

                    if (uiState.error != null) {
                        Text(
                            text = uiState.error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }

                    // 忘记密码
                    TextButton(
                        onClick = { /* TODO */ },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("忘记密码？", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 高级设置入口：设置服务器地址 / 端口（连不上时改为 USB 直连电脑的地址）
            var showAdvanced by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = { showAdvanced = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("⚙ 高级设置（服务器地址 / 端口）", style = MaterialTheme.typography.titleSmall)
            }

            if (showAdvanced) {
                ServerConfigDialog(
                    initial = serverUrl,
                    onDismiss = { showAdvanced = false },
                    onSave = { url ->
                        viewModel.saveServerUrl(url)
                        Toast.makeText(context, "已保存，立即生效", Toast.LENGTH_SHORT).show()
                        showAdvanced = false
                    }
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "登录即表示同意《用户协议》和《隐私政策》",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun LoginModeToggle(
    isSmsMode: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(
                color = PadGuardColors.SectionBg,
                shape = RoundedCornerShape(22.dp)
            ),
        horizontalArrangement = Arrangement.Center
    ) {
        val selectedIndex = if (isSmsMode) 1 else 0
        listOf("密码登录", "短信登录").forEachIndexed { index, label ->
            val isSelected = index == selectedIndex
            TextButton(
                onClick = { if (!isSelected) onToggle() },
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
            ) {
                Text(
                    text = label,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.titleSmall
                )
            }
        }
    }
}

@Composable
private fun PasswordLoginForm(
    phone: String,
    password: String,
    onPhoneChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLogin: () -> Unit,
    isLoading: Boolean
) {
    var passwordVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    OutlinedTextField(
        value = phone,
        onValueChange = onPhoneChange,
        label = { Text("手机号") },
        placeholder = { Text("请输入手机号") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.dp)
    )

    Spacer(modifier = Modifier.height(16.dp))

    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text("密码") },
        placeholder = { Text("请输入密码") },
        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(
                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                )
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onLogin() }),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.dp)
    )

    Spacer(modifier = Modifier.height(24.dp))

    Button(
        onClick = onLogin,
        enabled = phone.isNotBlank() && password.isNotBlank() && !isLoading,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
        } else {
            Text("登 录", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun SmsLoginForm(
    phone: String,
    code: String,
    onPhoneChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onSendCode: () -> Unit,
    isCodeSending: Boolean,
    countdown: Int,
    onLogin: () -> Unit
) {
    OutlinedTextField(
        value = phone,
        onValueChange = onPhoneChange,
        label = { Text("手机号") },
        placeholder = { Text("请输入手机号") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.dp)
    )

    Spacer(modifier = Modifier.height(16.dp))

    Row(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = code,
            onValueChange = onCodeChange,
            label = { Text("验证码") },
            placeholder = { Text("6位验证码") },
            modifier = Modifier.weight(1f),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        OutlinedButton(
            onClick = onSendCode,
            enabled = phone.length >= 11 && !isCodeSending && countdown == 0,
            modifier = Modifier.height(56.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(if (countdown > 0) "${countdown}s" else "获取验证码")
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    Button(
        onClick = onLogin,
        enabled = phone.isNotBlank() && code.length == 6,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text("登 录", style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * 高级设置弹窗：拆分编辑服务器 地址 / 端口 / 接口前缀，保存时拼回完整 baseUrl。
 *
 * 完整地址实时预览，降低手填出错概率（USB 直连场景尤其有用）。
 * 仅改 [ServerPrefs] 里的完整 baseUrl，[com.padguard.parent.data.remote.HostSelectionInterceptor]
 * 会在下一次请求时自动套用，无需重启 App。
 */
@Composable
private fun ServerConfigDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    val cfg = remember(initial) { parseServerConfig(initial) }
    var host by remember { mutableStateOf(cfg.host) }
    var port by remember { mutableStateOf(cfg.port) }
    var path by remember { mutableStateOf(cfg.path) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val built = buildServerConfig(host, port, path)
                if (built == null) {
                    error = "请填写有效的服务器地址与端口（1–65535）"
                    return@TextButton
                }
                onSave(built)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("高级设置 · 服务器配置") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim(); error = null },
                    label = { Text("服务器地址") },
                    placeholder = { Text("如 192.168.1.10") },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() }; error = null },
                    label = { Text("端口") },
                    placeholder = { Text("如 8090") },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it; error = null },
                    label = { Text("接口前缀（可选）") },
                    placeholder = { Text("如 /v1 或 /api/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
                val preview = buildServerConfig(host, port, path)
                Text(
                    text = "完整地址：${preview ?: "（请补全地址与端口）"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                if (error != null) {
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    text = "默认已填好当前地址。若连不上，请改为「手机用 USB 线直连电脑」时电脑显示的地址（见安装说明）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    )
}
