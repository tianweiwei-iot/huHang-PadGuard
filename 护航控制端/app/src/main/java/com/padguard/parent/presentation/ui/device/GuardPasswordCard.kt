package com.padguard.presentation.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.padguard.presentation.ui.components.SoftCard
import com.padguard.presentation.ui.theme.PadGuardColors

/**
 * 操作密码闸门卡片（C 端软拦截方案核心）。
 *
 * ## 它解决什么
 * 家长开启后，孩子要对平板做「改动」——进入系统设置、卸载应用、安装未知来源应用——
 * 都必须先输入家长在这里设的密码。密码由服务端按 SHA-256(明文) 的 Base64 哈希下发，
 * 孩子端只存哈希、明文永不出机；校验时孩子端本地重算比对。
 *
 * ## 能力边界必须写清（避免家长误判）
 * 这是**软拦截**：靠孩子端无障碍服务在检测到进入设置/卸载/安装界面时弹出自家密码框并顶回，
 * 不依赖设备所有者(DO)（存量 C 端平板无法在不恢复出厂的前提下成为 DO）。
 * 因此：
 * - 必须在「设置 → 无障碍」里手动开启该服务，未开启则闸门形同虚设（卡片内已提示）；
 * - 浏览器/应用市场**内部的下载点击**发生在第三方进程内，无障碍只能在「安装确认」这一步拦截，
 *   无法阻止在商店里点下载按钮本身——这点如实体现在说明里。
 */
@Composable
fun GuardPasswordCard(
    set: Boolean,
    busy: Boolean,
    onSet: (String) -> Unit,
    onClear: () -> Unit
) {
    var draft by remember(set) { mutableStateOf("") }
    val canSubmit = draft.length >= 4

    SoftCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = PadGuardColors.PrimaryBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text("设备操作密码", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            if (set) "已开启：改动设备需输密码" else "关闭：孩子可直接改动设备",
                            fontSize = 12.sp,
                            color = PadGuardColors.TextSecondary
                        )
                    }
                }
            }

            OutlinedTextField(
                value = draft,
                onValueChange = { if (it.length <= 32) draft = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                label = { Text("家长密码（至少 4 位）") },
                placeholder = { Text("孩子改动设备时输入的密码") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )

            Button(
                onClick = { onSet(draft) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && canSubmit,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PadGuardColors.PrimaryBlue)
            ) {
                Text(if (busy) "同步中…" else if (set) "修改密码" else "开启并同步")
            }

            if (set) {
                androidx.compose.material3.OutlinedButton(
                    onClick = onClear,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("清除密码")
                }
            }

            Surface(
                color = PadGuardColors.Amber.copy(alpha = 0.10f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = "开启后，孩子进入系统设置、卸载应用、安装未知来源应用都需输入此密码；" +
                        "密码经服务端哈希后下发，孩子端只存哈希、明文不出设备。" +
                        "该拦截依赖孩子端「无障碍服务」，请在平板上于「设置 → 无障碍」中开启。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = PadGuardColors.TextSecondary,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
    }
}
