package com.padguard.parent.presentation.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
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
import com.padguard.parent.BuildConfig
import com.padguard.parent.data.remote.EndpointSafety
import com.padguard.parent.data.remote.LoginFailureAdvisor
import com.padguard.parent.data.remote.ServerProbe
import com.padguard.parent.presentation.ui.theme.PadGuardColors
import kotlinx.coroutines.launch
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
    /**
     * 会话被动失效的原因（令牌过期 / 别处改密 / 账号被停用）。
     * 由导航层从 TokenManager 取出后传入；主动退出登录时为 null（不需要解释）。
     */
    sessionNotice: String? = null,
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

            // 会话被动失效的原因横幅。
            // 不加这一条时的实际体验：家长在另一台手机改了密码（或令牌到期），
            // 本机下一次请求 401 → 本地会话被清 → 整栈退回登录页，全程一句解释都没有，
            // 家长只会认为"App 又抽风了"。这里把原因摆在要动手的位置上。
            if (!sessionNotice.isNullOrBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = sessionNotice.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }


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
                            onPhoneChange = viewModel::updatePhone,
                            onPasswordChange = viewModel::updatePassword,
                            onSelectHistory = viewModel::selectHistoryAccount,
                            onRemoveHistory = viewModel::removeHistoryAccount,
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

                    // 失败原因之后的"下一步"：只说"用户不存在"等于把球踢回给用户。
                    //
                    // 这里只做"改写既有入口"，不新增第二个按钮 —— 否则同屏会出现
                    // 『该手机号还没有账号，去注册』与『还没有账号？立即注册』两个一模一样的入口。
                    // 连接类失败不在此加动作：下方「高级设置」会自动展开并可点「测试连接」。
                    val notRegistered = !uiState.isRegisterMode &&
                        LoginFailureAdvisor.suggestsRegistration(uiState.error)
                    val wrongPassword = !uiState.isRegisterMode &&
                        LoginFailureAdvisor.suggestsPasswordReset(uiState.error)

                    // 登录 ↔ 注册 互转入口。
                    // 必须显眼：新装的 App 里没有账号，若只放登录框，
                    // 用户会以为"要用初始账号登录"，而系统其实已经改为自助注册。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = when {
                                notRegistered -> "该手机号还没有账号？"
                                uiState.isRegisterMode -> "已有账号？"
                                else -> "还没有账号？"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        // 判定为"没注册"时用实心按钮：这是此刻唯一能走通的动作，
                        // 跟"密码错/被冻结"那些应该止步的情况必须区分开。
                        if (notRegistered) {
                            Button(
                                onClick = viewModel::toggleRegisterMode,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                modifier = Modifier.padding(start = 8.dp)
                            ) { Text("去注册") }
                        } else {
                            TextButton(onClick = viewModel::toggleRegisterMode) {
                                Text(
                                    text = if (uiState.isRegisterMode) "返回登录" else "立即注册",
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    // 忘记密码（仅登录模式有意义：注册流程里还没有可找回的密码）。
                    // 口令输错时把文案换成更具体的引导，其余时候保持安静，不做无谓的强调。
                    if (!uiState.isRegisterMode) {
                        TextButton(
                            onClick = viewModel::forgotPassword,
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text(
                                text = if (wrongPassword) "忘记了密码？去重置" else "忘记密码？",
                                color = MaterialTheme.colorScheme.primary
                            )
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
                // 失败原因是"连不上服务器"时自动展开地址表单：
                // 文案里已经把出错地址写清楚了，紧接着给出可改的输入框与「测试连接」，
                // 用户不用再自己去找入口（"不知道在哪改地址"是最常见的现场反馈）。
                autoExpandOn = uiState.error.takeIf { ServerProbe.looksLikeConnectionFailure(it) },
                onSave = { url ->
                    val ok = viewModel.saveServerUrl(url)
                    Toast.makeText(
                        context,
                        if (ok) "已保存，立即生效" else "地址无效或不安全，请查看下方提示",
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

            // 协议：必须**主动勾选**同意（应用市场审核硬指标，未成年人产品尤甚）。
            // 「登录即表示同意」是默示同意，会被判定为未取得有效同意而拒绝上架。
            // 协议正文仍可点击查看；勾选状态由 ViewModel 持久化，协议改版后需重新勾选。
            var showAgreement by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { viewModel.agreeToPolicies() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Checkbox(
                    checked = uiState.consentAgreed,
                    onCheckedChange = { checked ->
                        // 允许取消勾选 = 撤回同意。撤回必须真实清掉本地同意留痕，
                        // 否则 UI 显示已取消、状态仍是已同意，属于"假撤回"（PIPL 第 15 条）。
                        if (checked) viewModel.agreeToPolicies() else viewModel.declinePolicies()
                    }
                )
                Text(
                    text = "我已阅读并同意",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "《用户协议》和《隐私政策》",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { showAgreement = true }
                )
            }

            // 简易协议页（本地承载，避免依赖未备案的域名/URL）。
            if (showAgreement) {
                AgreementDialog(
                    onDismiss = { showAgreement = false },
                    onAgree = { viewModel.agreeToPolicies() },
                    onDecline = { viewModel.declinePolicies() }
                )
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
                                    text = "已记住账号",
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
    onPhoneChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSelectHistory: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
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

    // 这里刻意**没有**「记住密码」复选框。
    //
    // 早期版本有这个勾选框，但 AccountPrefs 出于安全从不持久化家长密码（设备被 root 或
    // 备份提取即等同于把家长门户钥匙交给孩子），于是勾选后密码永远不会被回填 ——
    // 一个点了永远不生效的开关，比没有这个开关更糟：家长会反复勾选并认为 App 坏了。
    // 现在改为一句如实说明，界面承诺与实际行为保持一致。
    Text(
        text = "为保护账号安全，本机只记住登录手机号，不保存密码",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )

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
    onReset: () -> Unit,
    autoExpandOn: String? = null
) {
    var expanded by remember { mutableStateOf(false) }

    // 只在"连接类失败"出现时自动展开一次；用户手动收起后不会被再次弹开
    // （key 是错误文案本身，同一条错误重复出现不会反复触发）。
    LaunchedEffect(autoExpandOn) {
        if (autoExpandOn != null) expanded = true
    }

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
    // 测试连接：不需要先保存就能验证表单里的地址是否真的有服务端在跑。
    // 之所以值得单独做，是因为"保存后去登录、失败再回来改地址"这个循环一次要十几秒，
    // 而地址错了 90% 的用户会先去怀疑密码 —— 探测把这条反馈压到 1 次点击。
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var probeResult by remember { mutableStateOf<ServerProbe.Result?>(null) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = host,
            onValueChange = { host = it.trim(); error = null; probeResult = null },
            label = { Text("服务器地址") },
            placeholder = { Text("如 192.168.1.10") },
            singleLine = true,
            isError = error != null,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter { c -> c.isDigit() }; error = null; probeResult = null },
            label = { Text("端口") },
            placeholder = { Text("如 8090") },
            singleLine = true,
            isError = error != null,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        )
        OutlinedTextField(
            value = path,
            onValueChange = { path = it; error = null; probeResult = null },
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
                    onClick = { scheme = value; error = null; probeResult = null },
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

        // 测试连接：直接用表单当前值探测（不必先保存），命中 /health 即说明地址可用。
        // 这一行是为「地址填错却不自知」准备的：用户点一下就能区分"服务器没连上"和
        // "连上了但账号/口令不对"，不用再靠反复试登录去猜。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = {
                    val built = buildServerConfig(host, port, path, scheme)
                    if (built == null) {
                        error = "请填写有效的服务器地址与端口（1–65535）"
                        probeResult = null
                    } else {
                        error = null
                        scope.launch {
                            testing = true
                            probeResult = null
                            probeResult = ServerProbe.check(built)
                            testing = false
                        }
                    }
                },
                enabled = !testing,
                shape = RoundedCornerShape(10.dp)
            ) {
                if (testing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (testing) "正在测试…" else "测试连接")
            }
        }
        probeResult?.let { r ->
            Text(
                text = r.message,
                color = if (r.ok) PadGuardColors.SuccessGreen else MaterialTheme.colorScheme.error,
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
                        // 安全准入与网络层（HostSelectionInterceptor）用同一套规则，避免
                        // 「表单让存、请求却被拦」这种用户无从自救的割裂状态。
                        // 公网 http 会在这里被明确拒绝并展示原因；内网 http 仍然放行。
                        when (val verdict = EndpointSafety.check(built, BuildConfig.ALLOW_CLEARTEXT_PUBLIC)) {
                            is EndpointSafety.Verdict.Rejected -> error = verdict.reason
                            is EndpointSafety.Verdict.Allowed -> {
                                error = null
                                onSave(verdict.normalizedUrl)
                            }
                        }
                    }
                },
                shape = RoundedCornerShape(10.dp)
            ) { Text("保存") }
        }

        Text(
            text = "默认已填好当前地址。若连不上，请改为「手机用 USB 线直连电脑」时电脑显示的地址（见安装说明）。" +
                "家庭/校园内网可用 HTTP；公网地址必须使用 HTTPS（会在保存时校验）。",
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
 * 协议要点弹窗，并提供**随包全文**展开入口。
 *
 * 法务复核（2026-11-25 第二轮）：旧版只展示要点，并以"全文在随包文件中"的说法代替真实可达的全文，
 * 但 APK 内并无全文，用户实际无法查阅完整条款 —— 这是 PIPL 第 17 条的告知瑕疵，
 * 也是应用市场审核的高发驳回点。现把两份 md 原文内置到 assets/legal/，
 * 点"查看完整全文"即可在 App 内滚动阅读，不依赖尚未备案的外链域名。
 *
 * [readOnly] 用于「关于」页：此时只提供关闭按钮，不触发同意/撤回状态变更。
 */
@Composable
private fun rememberLegalText(assetPath: String): String {
    val context = LocalContext.current
    return remember(assetPath) {
        runCatching {
            context.assets.open(assetPath).bufferedReader().use { it.readText() }
        }.getOrElse {
            "【条款文本加载失败】请通过客服邮箱 support@padguard.com 索取完整版本。"
        }
    }
}

@Composable
internal fun AgreementDialog(
    onDismiss: () -> Unit,
    onAgree: () -> Unit = {},
    onDecline: () -> Unit = {},
    readOnly: Boolean = false
) {
    var expandedTerms by remember { mutableStateOf(false) }
    var expandedPrivacy by remember { mutableStateOf(false) }
    val fullTerms = rememberLegalText("legal/terms-of-service.md")
    val fullPrivacy = rememberLegalText("legal/privacy-policy.md")

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            if (readOnly) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            } else {
                TextButton(onClick = {
                    onAgree()
                    onDismiss()
                }) { Text("同意并勾选") }
            }
        },
        dismissButton = {
            if (!readOnly) {
                TextButton(onClick = {
                    onDecline()
                    onDismiss()
                }) { Text("不同意") }
            } else null
        },
        title = { Text("用户协议与隐私政策") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "版本：《用户协议》v1.1 /《隐私政策》v2.1，均自 2026-11-25 起生效。" +
                        "勾选即表示您已阅读并同意两份完整条款。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "《用户协议》",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "1. 本软件为家长监护类工具，您应是年满 18 周岁的监护人，且仅用于监护目的。\n\n" +
                        "2. 家长与孩子设备需绑定后使用，绑定关系由家长端发起并建立；孩子端复用家长账号，" +
                        "请妥善保管密码，勿与孩子共享。\n\n" +
                        "3. 远程锁屏、应用管理、位置查看等能力仅限监护目的；我们不承诺绝对防绕过，" +
                        "未启用设备所有者模式时卸载、强停等情形可能突破管控，能力边界已在 App 内如实展示。" +
                        "但因产品缺陷或我们未履行已承诺的加固义务导致的失效，我们依法承担责任。\n\n" +
                        "4. 位置信息受信号与省电策略影响存在误差与延迟，息屏期间不更新，不得作为紧急救援依据；" +
                        "遇危险请立即拨打 110 / 120 / 119。\n\n" +
                        "5. 截图、环境音等功能会一并采集在场第三人（老师、同学、家人）的对话或影像，" +
                        "请事先告知并取得同意，不得传播或用于监护以外目的。\n\n" +
                        "6. 套餐为一次性购买、不自动续费，支付由微信完成；已扣款未到账或需退款，请邮件 " +
                        "support@padguard.com。",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(
                    onClick = { expandedTerms = !expandedTerms },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (expandedTerms) "收起《用户协议》完整全文" else "查看《用户协议》完整全文")
                }
                if (expandedTerms) {
                    Text(
                        text = fullTerms,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "《隐私政策》",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "1. 官方托管服务由我们运营，我们是所处理个人信息的处理者；自建部署时数据由家长自管。" +
                        "为客服与运维，授权人员在角色权限控制与操作审计下，可能接触必要的账号与使用数据，" +
                        "不用于广告或画像。\n\n" +
                        "2. 仅收集监护必需信息：设备状态、应用时长、位置、网址过滤记录，以及家长主动发起的" +
                        "截图/录屏/环境音；家长账号使用手机号，支付敏感信息由微信处理，我们不接触。\n\n" +
                        "3. 位置仅在孩子端前台使用时采集；官方托管强制 HTTPS，自建局域网若用 HTTP 存在" +
                        "被嗅探风险，请仅在可信网络部署。\n\n" +
                        "4. 保存期限：用量日志默认 30 天，位置与截图/录屏/录音默认 90 天，到期自动删除；" +
                        "订单与审计记录按法规保留。注销后业务数据物理删除、账号匿名化，并非所有记录均被抹除。\n\n" +
                        "5. 第三方 SDK：① 高德地图 SDK（地图展示与地址反查，可能收集设备标识与位置信息）；" +
                        "② 微信开放平台 SDK（购买订阅时调起支付，可能收集设备标识与支付订单信息）。" +
                        "二者均由功能触发时才工作、不常驻采集，处理分别遵循高德与微信隐私政策；" +
                        "未集成广告、统计或崩溃上报类 SDK。\n\n" +
                        "6. 您可在「数据权利与合规」页自助导出数据文件、撤回/恢复同意、申请注销或申诉；" +
                        "不满 14 周岁的监护人单独同意亦在该页提交。查询、更正、删除请邮件 " +
                        "support@padguard.com，15 个工作日内答复。",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(
                    onClick = { expandedPrivacy = !expandedPrivacy },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (expandedPrivacy) "收起《隐私政策》完整全文" else "查看《隐私政策》完整全文")
                }
                if (expandedPrivacy) {
                    Text(
                        text = fullPrivacy,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!readOnly) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "如不同意，将无法使用本产品；您可在「关于」页随时查阅全文，" +
                            "在「数据权利与合规」页撤回同意并注销账号。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
}
