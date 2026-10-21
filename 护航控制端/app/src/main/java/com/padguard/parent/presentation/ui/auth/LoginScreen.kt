package com.padguard.parent.presentation.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import com.padguard.parent.presentation.ui.theme.PadGuardColors
import com.padguard.parent.presentation.ui.components.SoftCard
import com.padguard.parent.di.AccountPrefs
import com.padguard.parent.presentation.viewmodel.LoginViewModel
import com.padguard.parent.presentation.viewmodel.buildServerConfig
import com.padguard.parent.presentation.viewmodel.parseServerConfig
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast

/**
 * 登录页 (优先级)
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
        // 必须可滚动：登录卡片 + 高级设置 + 协议文字的总高度在小屏机型上会超过一屏，
        // 之前不可滚动时，"高级设置"被顶到屏幕外且滚不回来，用户会以为"登录页根本没有这个入口"。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

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

            // 登录方式切换入口：**有意不提供**。
            // 短信登录在服务端未开通（sms-login-enabled=false，且无短信服务商），
            // 发码与校验都不成立。若摆出一个"短信登录"tab，用户点进去只会发现
            // 验证码永远收不到 —— 那比不给这个选项更糟。
            //
            // 下方 `uiState.isSmsMode -> SmsLoginForm(...)` 分支因此当前不可达
            // （isSmsMode 恒为 false，且切换方法已随 LoginModeToggle 一并删除）。
            // 它不是空壳：数据层 `AuthRepository.sendSmsCode / loginWithSmsCode` 与
            // SmsLoginForm 本身均已实现，服务端开通后补一个切换入口即可启用，
            // 保留分支是为了让那次改动只需加一个入口，不必重写整条链路。

            Spacer(modifier = Modifier.height(24.dp))

            // 登录 / 注册表单卡片
            SoftCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(24.dp)
                ) {
                    when {
                        uiState.isRegisterMode -> RegisterForm(
                            phone = uiState.phone,
                            password = uiState.password,
                            confirmPassword = uiState.confirmPassword,
                            nickname = uiState.nickname,
                            onPhoneChange = viewModel::updatePhone,
                            onPasswordChange = viewModel::updatePassword,
                            onConfirmChange = viewModel::updateConfirmPassword,
                            onNicknameChange = viewModel::updateNickname,
                            onRegister = viewModel::register,
                            isLoading = uiState.isLoading
                        )
                        uiState.isSmsMode -> SmsLoginForm(
                            phone = uiState.phone,
                            code = uiState.smsCode,
                            onPhoneChange = viewModel::updatePhone,
                            onCodeChange = viewModel::updateSmsCode,
                            onSendCode = { viewModel.sendSmsCode(uiState.phone) },
                            isCodeSending = uiState.isSendingCode,
                            countdown = uiState.countdown,
                            onLogin = { viewModel.loginWithSms() }
                        )
                        else -> PasswordLoginForm(
                            phone = uiState.phone,
                            password = uiState.password,
                            history = uiState.history,
                            rememberPassword = uiState.rememberPassword,
                            onPhoneChange = viewModel::updatePhone,
                            onPasswordChange = viewModel::updatePassword,
                            onSelectHistory = viewModel::selectHistoryAccount,
                            onRemoveHistory = viewModel::removeHistoryAccount,
                            onRememberChange = viewModel::toggleRememberPassword,
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

                    // 登录 ↔ 注册 互转入口。
                    // 必须显眼：新装的 App 里没有账号，若只放登录框，
                    // 用户会以为"要用初始账号登录"，而系统其实已经改为自助注册。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (uiState.isRegisterMode) "已有账号？" else "还没有账号？",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        TextButton(onClick = viewModel::toggleRegisterMode) {
                            Text(
                                text = if (uiState.isRegisterMode) "返回登录" else "立即注册",
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // 忘记密码（仅登录模式有意义：注册流程里还没有可找回的密码）
                    if (!uiState.isRegisterMode) {
                        TextButton(
                            onClick = viewModel::forgotPassword,
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("忘记密码？", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            // 忘记密码引导弹窗（客服人工重置流程）
            if (uiState.showForgotPassword) {
                AlertDialog(
                    onDismissRequest = viewModel::dismissForgotPassword,
                    confirmButton = {
                        TextButton(onClick = viewModel::dismissForgotPassword) {
                            Text("我知道了")
                        }
                    },
                    title = { Text("忘记密码") },
                    text = {
                        Text(
                            "当前暂不支持自助找回密码。请联系客服进行人工重置：\n\n" +
                            "· 提供注册时使用的手机号；\n" +
                            "· 客服核实身份后为您重置密码。\n\n" +
                            "客服联系方式请见「我的」页，或邮件至 support@padguard.com。"
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 高级设置（下拉展开）：服务器 IP / 端口。
            //
            // 为什么必须放在登录页：服务器地址填错时，人根本登不进去，
            // 若只有「我的」页能改，就形成「连不上 → 登不进 → 改不了」的死循环。
            // 早期这里是一个"弹窗按钮"，但因为整页不可滚动，它在小屏上被挤出可视区，
            // 表现就是"登录页没有高级设置"。
            AdvancedSettingsSection(
                serverUrl = serverUrl,
                onSave = { url ->
                    val ok = viewModel.saveServerUrl(url)
                    Toast.makeText(
                        context,
                        if (ok) "已保存，立即生效" else "地址无效，请检查后重试",
                        Toast.LENGTH_SHORT
                    ).show()
                    ok
                },
                onReset = {
                    viewModel.resetServerUrl()
                    Toast.makeText(context, "已恢复默认地址", Toast.LENGTH_SHORT).show()
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 协议改为可点击，点击跳「简易协议页」（下方 AgreementDialog 承载）。
            // 早期是纯文本、点击无任何反应，商店隐私审核硬伤（未成年人产品更敏感）。
            var showAgreement by remember { mutableStateOf(false) }
            Text(
                text = "登录即表示同意《用户协议》和《隐私政策》",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAgreement = true }
            )

            // 简易协议页（本地承载，避免依赖未备案的域名/URL）。
            if (showAgreement) {
                AgreementDialog(onDismiss = { showAgreement = false })
            }
        }
    }
}

/**
 * 「密码登录 / 短信登录」切换器 —— **已删除**。
 *
 * 短信登录当前在服务端未开通（`sms-login-enabled=false`，且未接入短信服务商），
 * 发码与校验都不成立。此前这个组件连同 `LoginViewModel.toggleLoginMode()` 一并保留但**零调用**，
 * 属于典型死代码：看起来是一套可选登录方式，实际点了也不会出现。现已删除。
 *
 * 若后续开通短信登录，恢复路径是：在此处补回一个切换入口，调用
 * `viewModel.setLoginMode(sms = true)`（若无该方法则新增），
 * 数据层 `AuthRepository.sendSmsCode / loginWithSmsCode` 与 [SmsLoginForm] 均已实现，无需重写。
 */

/**
 * 注册表单：手机号 + 密码 + 确认密码 + 可选昵称。
 *
 * 「确认密码」只做端上校验、不上传服务端：服务端模型里没有这个字段，
 * 端上拦截掉明显的手误，可以省掉一次往返和"注册成功了但密码不是我想要的"这种困惑。
 *
 * 手机号格式与"是否已注册"这类规则交给服务端判定，端上不重复一份正则 ——
 * 否则将来号段规则调整就要同时改两端，属于典型的规则双写漂移。
 */
@Composable
private fun RegisterForm(
    phone: String,
    password: String,
    confirmPassword: String,
    nickname: String,
    onPhoneChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConfirmChange: (String) -> Unit,
    onNicknameChange: (String) -> Unit,
    onRegister: () -> Unit,
    isLoading: Boolean
) {
    var passwordVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    OutlinedTextField(
        value = phone,
        onValueChange = onPhoneChange,
        label = { Text("手机号") },
        placeholder = { Text("请输入 11 位手机号") },
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
        placeholder = { Text("至少 6 位") },
        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(
                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                )
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.dp)
    )

    Spacer(modifier = Modifier.height(16.dp))

    OutlinedTextField(
        value = confirmPassword,
        onValueChange = onConfirmChange,
        label = { Text("确认密码") },
        placeholder = { Text("请再次输入密码") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.dp)
    )

    Spacer(modifier = Modifier.height(16.dp))

    OutlinedTextField(
        value = nickname,
        onValueChange = onNicknameChange,
        label = { Text("昵称（可选）") },
        placeholder = { Text("留空则自动生成") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onRegister() }),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.dp)
    )

    Spacer(modifier = Modifier.height(12.dp))

    // 权益前置：注册前就写明送多久试用，避免用户注册完不知道自己有什么、到期时觉得被"突然收费"
    Text(
        text = "注册成功即开通免费试用，到期后可在「我的 → 订阅」中续费",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )

    Spacer(modifier = Modifier.height(16.dp))

    Button(
        onClick = onRegister,
        enabled = !isLoading,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(if (isLoading) "注册中…" else "注册并进入")
    }
}

/**
 * 历史账号下拉：点击展开，选中即回填账号与已保存的密码，右侧 × 可删除该条。
 *
 * 用 [DropdownMenu] 而不是 ExposedDropdownMenu：[OutlinedTextField] 保持普通可编辑状态，
 * 用户既能直接手输新号码，也能从历史里挑，两者互不干扰。
 */
@Composable
private fun HistoryAccountField(
    phone: String,
    history: List<AccountPrefs.SavedAccount>,
    onPhoneChange: (String) -> Unit,
    onSelectHistory: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onNext: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = phone,
            onValueChange = { onPhoneChange(it); expanded = false },
            label = { Text("手机号") },
            placeholder = { Text("请输入手机号") },
            trailingIcon = {
                IconButton(onClick = { expanded = true }, enabled = history.isNotEmpty()) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                        contentDescription = "选择历史账号",
                        tint = if (history.isEmpty()) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { onNext() }),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        DropdownMenu(
            expanded = expanded && history.isNotEmpty(),
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            history.forEach { acc ->
                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(acc.phone, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = if (acc.password != null) "已记住密码" else "仅记住账号",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        }
                    },
                    onClick = { onSelectHistory(acc.phone); expanded = false },
                    trailingIcon = {
                        IconButton(onClick = {
                            onRemoveHistory(acc.phone)
                            if (history.size <= 1) expanded = false
                        }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "删除该历史账号",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                            )
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun PasswordLoginForm(
    phone: String,
    password: String,
    history: List<AccountPrefs.SavedAccount>,
    rememberPassword: Boolean,
    onPhoneChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSelectHistory: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onRememberChange: (Boolean) -> Unit,
    onLogin: () -> Unit,
    isLoading: Boolean
) {
    var passwordVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    HistoryAccountField(
        phone = phone,
        history = history,
        onPhoneChange = onPhoneChange,
        onSelectHistory = onSelectHistory,
        onRemoveHistory = onRemoveHistory,
        onNext = { focusManager.moveFocus(FocusDirection.Down) }
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

    Spacer(modifier = Modifier.height(4.dp))

    // 记住密码：勾选后密码随账号一起存到本地，下次进入自动回填；取消则立即清除
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = rememberPassword,
            onCheckedChange = onRememberChange,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "记住账号和密码",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onRememberChange(!rememberPassword) }
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

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
 * 登录页的「高级设置」下拉区。
 *
 * 折叠时只占一行（标题 + 当前地址 + 箭头），展开后原地铺开服务器配置表单，
 * 不跳转页面、不弹对话框——登录前唯一能改地址的地方，必须一眼可见、一步可改。
 */
@Composable
private fun AdvancedSettingsSection(
    serverUrl: String,
    onSave: (String) -> Boolean,
    onReset: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "高级设置 · 服务器地址",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = serverUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            if (expanded) {
                HorizontalDivider()
                ServerConfigEditor(
                    initial = serverUrl,
                    onSave = { url -> if (onSave(url)) expanded = false },
                    onCancel = null,
                    onReset = onReset,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

/**
 * 服务器配置表单：拆分编辑 地址 / 端口 / 接口前缀，保存时拼回完整 baseUrl。
 *
 * 登录页的下拉区与「我的」页的弹窗共用这一个实现，避免两处各写一套校验与预览逻辑
 * （历史上两处不一致最典型的后果就是：一处改了规则，另一处还在放行非法地址）。
 *
 * 完整地址实时预览，降低手填出错概率（USB 直连场景尤其有用）。
 * 仅改 [com.padguard.parent.di.ServerPrefs] 里的完整 baseUrl，
 * [com.padguard.parent.data.remote.HostSelectionInterceptor] 会在下一次请求时自动套用，无需重启 App。
 */
@Composable
fun ServerConfigEditor(
    initial: String,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
    onReset: (() -> Unit)? = null
) {
    val cfg = remember(initial) { parseServerConfig(initial) }
    var host by remember { mutableStateOf(cfg.host) }
    var port by remember { mutableStateOf(cfg.port) }
    var path by remember { mutableStateOf(cfg.path) }
    var scheme by remember { mutableStateOf(cfg.scheme) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = host,
            onValueChange = { host = it.trim(); error = null },
            label = { Text("服务器地址") },
            placeholder = { Text("如 8.148.68.183") },
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

        // 协议选择：默认回显解析出的 scheme（用户若原本填的是 https，必须保留，不能静默降级成明文 http）
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            listOf("http" to "HTTP", "https" to "HTTPS").forEach { (value, label) ->
                val selected = scheme == value
                OutlinedButton(
                    onClick = { scheme = value; error = null },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface
                    )
                ) { Text(label) }
            }
        }

        val preview = buildServerConfig(host, port, path, scheme)
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onReset != null) {
                OutlinedButton(onClick = onReset, shape = RoundedCornerShape(10.dp)) {
                    Text("恢复默认")
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            if (onCancel != null) {
                TextButton(onClick = onCancel) { Text("取消") }
                Spacer(modifier = Modifier.width(8.dp))
            }
            Button(
                onClick = {
                    val built = buildServerConfig(host, port, path, scheme)
                    if (built == null) {
                        error = "请填写有效的服务器地址与端口（1–65535）"
                    } else {
                        error = null
                        onSave(built)
                    }
                },
                shape = RoundedCornerShape(10.dp)
            ) { Text("保存") }
        }

        Text(
            text = "默认已填好当前地址。若连不上，请改为「手机用 USB 线直连电脑」时电脑显示的地址（见安装说明）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

/**
 * 「我的」页用的服务器配置弹窗（登录后也能改，不必退出登录）。
 * 表单部分复用 [ServerConfigEditor]，本函数只负责弹窗外壳。
 */
@Composable
fun ServerConfigDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // 保存/取消按钮由 ServerConfigEditor 渲染，这里留空避免两套按钮并存
        confirmButton = { },
        dismissButton = { },
        title = { Text("服务器地址") },
        text = {
            ServerConfigEditor(
                initial = initial,
                onSave = { url ->
                    onSave(url)
                    onDismiss()
                },
                onCancel = onDismiss,
                modifier = Modifier.verticalScroll(rememberScrollState())
            )
        }
    )
}

/**
 * 简易协议页。
 * 登录页协议文案点击后展示，承载《用户协议》与《隐私政策》要点。
 * 采用本地文本而非外链 URL：当前域名尚未完成 ICP 备案，外链不可达；
 * 后续备案完成后可替换为跳转正式协议页。
 */
@Composable
private fun AgreementDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("我已阅读") }
        },
        title = { Text("用户协议与隐私政策") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "《用户协议》",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "1. 本软件为家长监护类工具，用于帮助家长管理未成年人所使用的设备。" +
                        "使用本软件即表示您已了解并同意相关管控功能。\n\n" +
                        "2. 家长与孩子设备需绑定后使用，绑定关系由家长端发起并建立。\n\n" +
                        "3. 软件提供的远程锁屏、应用管理、位置查看等能力，仅限于监护目的使用。",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "《隐私政策》",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "1. 我们仅收集为提供监护功能所必需的信息（如设备状态、应用使用时长、位置等），" +
                        "且均经家长授权后采集。\n\n" +
                        "2. 位置等敏感信息在设备后台不读取，仅在响应家长指令或回到前台时补传。\n\n" +
                        "3. 我们不会向第三方出售或非法提供您的个人信息；数据存储与传输遵循相关安全规范。\n\n" +
                        "4. 您可通过客服申请查询、更正或删除您的个人信息。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    )
}
