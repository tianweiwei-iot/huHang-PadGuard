package com.padguard.child.bind

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.padguard.child.ui.settings.ServerConfigDialog

/**
 * 绑定流程 UI（说明书 §4.2 / §4.3）。
 *
 * 首屏「请绑定管控端」提供 **≥3 种绑定方式**（说明书硬性要求）：
 * ① 扫码绑定 ② 输入绑定码 ③ NFC 碰一碰。
 * 输码为既有可用链路；扫码 / NFC 解析出的绑定码统一经 [presetCode] 走
 * [BindViewModel.submit] 的既有提交流程，不重复实现绑定逻辑。
 */
@Composable
fun BindScreen(
    onBound: () -> Unit,
    presetCode: String? = null,
    onScanQr: () -> Unit = {},
    onStartNfc: () -> Unit = {},
    viewModel: BindViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val serverUrl by viewModel.serverUrl.collectAsState()
    var showAdvanced by remember { mutableStateOf(false) }

    LaunchedEffect(state.step) {
        if (state.step == BindStep.Success) onBound()
    }
    // 扫码 / NFC 解析出的绑定码：先填入输入框状态，再走既有提交（submit 无参，使用 state.code）
    LaunchedEffect(presetCode) {
        if (!presetCode.isNullOrBlank()) {
            viewModel.onCodeChanged(presetCode)
            viewModel.submit()
        }
    }
    BindContent(
        state = state,
        viewModel = viewModel,
        onCodeChanged = viewModel::onCodeChanged,
        onSubmit = viewModel::submit,
        onInputAccount = viewModel::goToInputAccount,
        onTestConnection = viewModel::testConnection,
        serverUrl = serverUrl,
        connectionResult = state.connectionResult,
        onAccountChanged = viewModel::onAccountChanged,
        onAccountPasswordChanged = viewModel::onAccountPasswordChanged,
        onSubmitAccount = viewModel::submitByAccount,
        onRetry = viewModel::retry,
        onInputCode = viewModel::goToInputCode,
        onBackToInput = viewModel::backToInput,
        onBackToWelcome = viewModel::backToWelcome,
        onScanQr = onScanQr,
        onStartNfc = onStartNfc,
        onAdvancedSettings = { showAdvanced = true }
    )

    if (showAdvanced) {
        ServerConfigDialog(
            initial = serverUrl,
            onDismiss = { showAdvanced = false },
            onSave = { url ->
                viewModel.setServerUrl(url)
                showAdvanced = false
            }
        )
    }
}

@Composable
private fun BindContent(
    state: BindUiState,
    viewModel: BindViewModel,
    onCodeChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onInputAccount: () -> Unit,
    onTestConnection: () -> Unit,
    serverUrl: String,
    connectionResult: String?,
    onAccountChanged: (String) -> Unit,
    onAccountPasswordChanged: (String) -> Unit,
    onSubmitAccount: () -> Unit,
    onRetry: () -> Unit,
    onInputCode: () -> Unit,
    onBackToInput: () -> Unit,
    onBackToWelcome: () -> Unit,
    onScanQr: () -> Unit,
    onStartNfc: () -> Unit,
    onAdvancedSettings: () -> Unit
) {
    // 绑定成功后若需激活设备管理员，由本 launcher 拉起系统「激活设备管理员」确认框；
    // 无论确认或取消，返回后都推进到成功页（未激活则由守护服务后续持续引导）。
    val deviceAdminLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.advanceToSuccess() }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(40.dp))
            Text(
                text = "护航成长",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "被管控端",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(48.dp))
            when (state.step) {
                BindStep.Welcome -> MethodChoiceStep(
                    onScanQr = onScanQr,
                    onInputCode = onInputCode,
                    onNfc = onStartNfc,
                    onInputAccount = onInputAccount,
                    onAdvancedSettings = onAdvancedSettings,
                    serverUrl = serverUrl,
                    connectionResult = state.connectionResult,
                    onTestConnection = onTestConnection
                )
                BindStep.InputAccount -> AccountBindStep(
                    account = state.account,
                    password = state.accountPassword,
                    errorMessage = state.errorMessage,
                    onAccountChanged = onAccountChanged,
                    onPasswordChanged = onAccountPasswordChanged,
                    onSubmit = onSubmitAccount
                )
                BindStep.InputCode -> InputCodeStep(
                    code = state.code,
                    errorMessage = state.errorMessage,
                    onCodeChanged = onCodeChanged,
                    onSubmit = onSubmit
                )
                BindStep.Submitting, BindStep.Waiting -> WaitingStep(
                    isSubmitting = state.step == BindStep.Submitting
                )
                BindStep.DeviceAdmin -> DeviceAdminStep(
                    onActivate = { deviceAdminLauncher.launch(viewModel.deviceAdminIntent()) },
                    onSkip = viewModel::advanceToSuccess
                )
                BindStep.Failed -> FailedStep(
                    message = state.errorMessage ?: "绑定失败",
                    onRetry = onRetry,
                    onBack = onBackToInput
                )
                BindStep.Success -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "绑定成功，正在进入…", style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "遇到问题？请联系家长或老师",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 绑定方式选择（说明书 §4.3 要求 ≥3 种）。
 * ① 扫码 ② 输入绑定码 ③ NFC 碰一碰，三卡片并列。
 */
@Composable
private fun MethodChoiceStep(
    onScanQr: () -> Unit,
    onInputCode: () -> Unit,
    onNfc: () -> Unit,
    onInputAccount: () -> Unit,
    onAdvancedSettings: () -> Unit,
    serverUrl: String,
    connectionResult: String?,
    onTestConnection: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "请绑定管控端",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "扫码或在管控端获取绑定码，绑定后由家长远程守护",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))

        BindMethodCard(
            title = "① 扫码绑定",
            desc = "打开家长端「添加设备」页的二维码，对准扫描即可",
            onClick = onScanQr
        )
        BindMethodCard(
            title = "② 输入绑定码",
            desc = "在家长端 App 中获取 6 位绑定码，手动输入",
            onClick = onInputCode
        )
        BindMethodCard(
            title = "③ NFC 碰一碰",
            desc = "将本设备背面贴近已开启 NFC 的家长端，自动读取",
            onClick = onNfc
        )
        BindMethodCard(
            title = "④ 账号密码绑定",
            desc = "直接输入家长端账号和密码绑定，不受配对码 10 分钟时效与相机权限限制",
            onClick = onInputAccount
        )

        // 防卸载能力分层说明。
        // 早期向导只强调"防卸载"，未区分设备所有者(Device Owner)与非 DO 模式的能力差异，
        // 家长易误以为任何情况下孩子都删不掉本应用。这里把事实讲清楚，避免预期落差。
        Card(
            onClick = { /* 纯说明卡片，无交互 */ },
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "关于防卸载",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "绑定后本应用会尽力防止被卸载（设备管理员 + 辅助功能守护）。但需注意：" +
                        "在未以「设备所有者（企业 / 课堂）模式」激活的平板上，防卸载是尽力而为——" +
                        "孩子仍可通过系统设置解除设备管理员后卸载，或通过恢复出厂设置绕过。" +
                        "真正不可卸载需由学校 / 机构通过 Device Owner 部署。家长端的远程锁屏、应用管控等能力不受影响。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
        }

        // 连接自检：绑定失败绝大多数是"服务器地址不通"，把地址与连通性直接摊开，
        // 避免用户只能看到一句笼统的"绑定失败"而无从下手。
        Text(
            text = "当前服务器：$serverUrl",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(
            onClick = onTestConnection,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("🔌 测试连接", style = MaterialTheme.typography.titleSmall)
        }
        if (!connectionResult.isNullOrBlank()) {
            Text(
                text = connectionResult,
                style = MaterialTheme.typography.bodySmall,
                color = if (connectionResult.startsWith("连接正常"))
                    MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 高级设置：绑定前即可设置服务器地址 / 端口（连不上管控端时改为 USB 直连电脑的地址）
        OutlinedButton(
            onClick = onAdvancedSettings,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("⚙ 高级设置（服务器地址 / 端口）", style = MaterialTheme.typography.titleSmall)
        }

        // 演示期提示：绑定码在家长端「添加设备」页生成，10 分钟内有效
        Text(
            text = "绑定码在家长端「添加设备」页生成，10 分钟内有效",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun BindMethodCard(title: String, desc: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun InputCodeStep(
    code: String,
    errorMessage: String?,
    onCodeChanged: (String) -> Unit,
    onSubmit: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "请输入 6 位绑定码",
            style = MaterialTheme.typography.titleMedium
        )
        OutlinedTextField(
            value = code,
            onValueChange = onCodeChanged,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
            supportingText = {
                Text(
                    text = errorMessage ?: "家长在家长端 App 中生成 6 位数字码",
                    color = if (errorMessage != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        )
        Button(
            onClick = { onSubmit() },
            enabled = code.length == 6,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "提交绑定")
        }
    }
}

/**
 * 方式④：账号密码绑定。
 *
 * 这是配对码 / 扫码之外的兜底链路——只要能连上服务器、账号密码正确就能绑定，
 * 不依赖家长端实时生成码，也不依赖相机授权。
 */
@Composable
private fun AccountBindStep(
    account: String,
    password: String,
    errorMessage: String?,
    onAccountChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onSubmit: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "用家长账号绑定",
            style = MaterialTheme.typography.titleMedium
        )
        OutlinedTextField(
            value = account,
            onValueChange = onAccountChanged,
            label = { Text("家长端账号（手机号）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChanged,
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        if (errorMessage != null) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Button(
            onClick = onSubmit,
            enabled = account.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "绑定")
        }
        Text(
            text = "绑定成功后账号密码会记录在本机，可在「我的」中查看或清除",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun WaitingStep(isSubmitting: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(48.dp))
        Text(
            text = if (isSubmitting) "正在提交…" else "等待家长在家长端 App 中确认",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = "请家长在家长端 App 中查看「设备绑定请求」并点击确认",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 绑定成功后的「激活设备管理员」引导步（2026-11-11 卸载防护复盘补丁）。
 *
 * ## 为什么必须有这一步
 * 绑定只完成"连服务器 + 存凭据"，并不激活设备管理员。若跳过，孩子端只是个普通 App，
 * 长按 / 进设置即可直接卸载（现场"未解绑却能卸载"的根因）。设备管理员是**非 DO 机型**
 * 上唯一能竖起的卸载防线：激活后系统要求"先停用管理员才能卸载"，并把"停用页"交给软门拦截。
 *
 * ## 不可硬阻塞
 * 部分厂商 ROM 上系统确认框可能不弹或被管家拦截；若此处把流程卡死，孩子会困在绑定页。
 * 因此提供「稍后」出口——跳过则由守护服务的卸载防御模块持续告警并引导补激活（治本仍需 DO）。
 */
@Composable
private fun DeviceAdminStep(
    onActivate: () -> Unit,
    onSkip: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "最后一步：开启防卸载保护",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "为防止孩子直接卸载本应用，请在此将「护航成长」设为设备管理员。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
                Text(
                    text = "系统会弹出确认框，请点「激活」。后续要卸载时需先停用本设备管理员，" +
                        "而停用会被家长密码与管理告警拦下。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
        Button(
            onClick = onActivate,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(text = "去激活设备管理员", style = MaterialTheme.typography.titleSmall)
        }
        OutlinedButton(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(text = "稍后再说", style = MaterialTheme.typography.titleSmall)
        }
        Text(
            text = "提示：未激活设备管理员时，本应用可被孩子直接在设置中卸载。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun FailedStep(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "绑定未成功",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack) { Text(text = "返回修改") }
            Button(onClick = onRetry) { Text(text = "重新提交") }
        }
    }
}
