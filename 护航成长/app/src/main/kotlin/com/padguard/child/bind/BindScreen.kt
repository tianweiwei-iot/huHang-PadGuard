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
        onCodeChanged = viewModel::onCodeChanged,
        onSubmit = viewModel::submit,
        onInputAccount = viewModel::goToInputAccount,
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
    onCodeChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onInputAccount: () -> Unit,
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
                    onAdvancedSettings = onAdvancedSettings
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
    onAdvancedSettings: () -> Unit
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
