package com.padguard.child.guard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 孩子为什么会在打开应用时撞上这道门禁。
 *
 * 两种来源的对外表现一致（都要家长输密码），但**文案必须如实区分**，
 * 否则家长无法判断是自己取消了授权，还是只开了"打开应用也要密码"。
 */
enum class AppAccessReason {
    /** 家长已取消该应用的使用授权（hidden）：没有密码绝不放行，属底线语义。 */
    REVOKED,

    /** 应用已授权，但家长开启了「打开受管控应用也要密码」。 */
    CONTROLLED
}

private fun titleFor(appLabel: String) = "打开「$appLabel」需要家长验证"

private fun descFor(reason: AppAccessReason) = when (reason) {
    AppAccessReason.REVOKED ->
        "该应用已被家长取消使用授权。请输入家长密码后临时打开，不输密码无法进入。"
    AppAccessReason.CONTROLLED ->
        "家长已开启「打开应用需验证」。请输入家长密码后打开该应用。"
}

/**
 * 门禁卡片顶部的圆形图标徽章（锁形）。
 *
 * 抽成共享组件：门禁卡片、未设密码提示卡、设备操作解锁页三处共用，
 * 保证"家长验证"这一类界面在视觉上是同一个家族，而不是三张各画各的皮。
 */
@Composable
internal fun GateIconBadge(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(64.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(30.dp)
        )
    }
}

/**
 * 应用访问门禁的**唯一**交互实现。
 *
 * 它被两处消费，共用同一份校验与文案，杜绝两个入口行为分叉：
 * - [com.padguard.child.KidHomeActivity]：无障碍服务不可用时的**就地兜底**对话框，
 *   桌面始终在背后可见；
 * - [AppAccessGateActivity]：常规路径（含无障碍服务拦截），以透明浮层的形式
 *   **直接盖在被管控应用之上** —— 孩子看到的是"应用还开着，上面浮了一层密码框"，
 *   而不是"被带进了管控系统的某个页面"。
 *
 * 校验由 [GuardPasswordManager.verifyWithLimit] 承担，本组件只负责呈现与状态，
 * 不做任何密码运算，也不缓存明文。
 */
@Composable
fun AppAccessGateDialog(
    appLabel: String,
    reason: AppAccessReason,
    manager: GuardPasswordManager,
    onDismiss: () -> Unit,
    onGranted: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(titleFor(appLabel)) },
        text = {
            AppAccessGateBody(
                reason = reason,
                manager = manager,
                onDismiss = onDismiss,
                onGranted = onGranted
            )
        },
        confirmButton = {},
        dismissButton = {}
    )
}

/**
 * 浮层形态的门禁卡片（[AppAccessGateActivity] 用）。
 *
 * 外层透明、内容居中成卡片，被管控应用与桌面在背后保持可见 —— 这正是
 * "在应用之上弹密码框"与"跳进管控系统页面"的区别所在。
 */
@Composable
fun AppAccessGateCard(
    appLabel: String,
    reason: AppAccessReason,
    manager: GuardPasswordManager,
    onDismiss: () -> Unit,
    onGranted: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth(0.88f)
            .wrapContentHeight()
            .widthIn(max = 420.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GateIconBadge(Icons.Outlined.Shield)
            Spacer(Modifier.height(16.dp))
            Text(
                titleFor(appLabel),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            AppAccessGateBody(
                reason = reason,
                manager = manager,
                onDismiss = onDismiss,
                onGranted = onGranted
            )
        }
    }
}

/**
 * 未设置「设备操作密码」时的提示（对话框形态，孩子桌面就地弹出用）。
 *
 * 必须与上面的门禁成对提供：密码没启用时若还弹密码框，输入任何内容都必然失败，
 * 家长只会看到"密码错误"，永远看不出真正原因是"尚未设置密码"。
 */
@Composable
fun AppAccessNoPasswordDialog(appLabel: String, reason: AppAccessReason, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (reason == AppAccessReason.REVOKED) "应用已取消授权" else "需要家长先设置密码")
        },
        text = {
            Text(
                "「$appLabel」需要家长密码才能打开，但当前尚未设置「设备操作密码」。\n" +
                    "请家长在控制端「设备详情」中设置后，这里才会弹出密码框。",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("我知道了") }
        },
        dismissButton = null
    )
}

/**
 * 未设置「设备操作密码」时的提示。
 *
 * 与有密码时的门禁**共用同一呈现层**（浮在应用之上），不再落到管控系统页面里。
 * 语义不变：本地没有任何可比对凭据，无法核验身份，因此只能如实告知家长先去设置，
 * 绝不因为"验不了"就放行。
 */
@Composable
fun AppAccessNoPasswordCard(appLabel: String, reason: AppAccessReason, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth(0.88f)
            .wrapContentHeight()
            .widthIn(max = 420.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GateIconBadge(Icons.Outlined.Info)
            Spacer(Modifier.height(16.dp))
            Text(
                if (reason == AppAccessReason.REVOKED) "应用已取消授权" else "需要家长先设置密码",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "「$appLabel」需要家长密码才能打开，但当前尚未设置「设备操作密码」。\n" +
                    "请家长在控制端「设备详情」中设置后，这里才会弹出密码框。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("我知道了")
            }
        }
    }
}

@Composable
private fun AppAccessGateBody(
    reason: AppAccessReason,
    manager: GuardPasswordManager,
    onDismiss: () -> Unit,
    onGranted: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var verifying by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(manager.isLockedOutNow()) }
    var visible by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // 密码框直接聚焦并拉起软键盘：家长在应用之上看到浮层的当下就能输入，
    // 不需要再点一次输入框（这一步省掉，才谈得上"实时弹出"）。
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    fun submit() {
        if (verifying || locked) return
        val plain = password
        if (plain.isEmpty()) {
            error = "请输入家长密码"
            return
        }
        verifying = true
        scope.launch {
            val r = runCatching { manager.verifyWithLimit(plain) }
                .getOrNull()
            verifying = false
            if (r == null) {
                error = "校验失败，请重试"
                return@launch
            }
            if (r.ok) {
                onGranted()
                return@launch
            }
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

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            descFor(reason),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
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
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.height(0.dp))
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
                Text(if (locked) "已锁定" else "验证并打开")
            }
        }
        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("取消", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

/**
 * 透明浮层的容器：撑满屏幕、点击卡片外部即取消，卡片居中。
 *
 * 放在这里而不是 Activity 内，是为了让"点外部取消"这一行为在两处形态里保持一致。
 */
@Composable
fun AppAccessGateOverlay(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                // 点卡片外部等同取消：浮层不该把人困住，也不该让点击穿透到应用/桌面误触
                onDismiss()
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            content()
        }
    }
}
