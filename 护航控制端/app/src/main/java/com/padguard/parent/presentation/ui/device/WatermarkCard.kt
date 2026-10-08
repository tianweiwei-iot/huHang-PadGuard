package com.padguard.presentation.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrandingWatermark
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.padguard.presentation.ui.components.SoftCard
import com.padguard.presentation.ui.theme.PadGuardColors

/**
 * 受管控水印卡片（P0-1）。
 *
 * ## 这张卡存在的真正原因：有一个诉求我们做不到
 * 家长提"录屏能不能别显示红点"时，指的是 Android 在 MediaProjection 会话期间
 * 由**系统**绘制的那个红色录制指示器。它画在系统层、不属于任何应用的窗口，
 * Device Owner、系统签名、无障碍服务全都关不掉 —— 这是 Android 的隐私红线，
 * 设计上就是为了防止应用在用户不知情时录制屏幕。
 *
 * 直接回一句"做不到"没有意义。于是这里提供一个**能做到且同样解决诉求**的替代：
 * 本应用自己叠加一个半透明水印，文案家长可自定义，可随时开关。
 * 界面上把这条边界写在卡里而不是藏进帮助文档，是为了避免家长反复尝试
 * "为什么关不掉红点"，最后误判成产品坏了。
 *
 * ## 文案为什么要限制 30 字
 * 水印是浮在孩子屏幕上的文字，过长会盖住应用内容影响正常使用，
 * 而家长写长文案通常只是没意识到这一点。这里在输入时提示上限，
 * 服务端也会再截断一次（双保险，避免绕过客户端直接调接口）。
 */
@Composable
fun WatermarkCard(
    enabled: Boolean,
    content: String,
    busy: Boolean,
    onApply: (Boolean, String) -> Unit
) {
    var draft by remember(content) { mutableStateOf(content) }
    val dirty = draft != content || !enabled

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
                        imageVector = Icons.Default.BrandingWatermark,
                        contentDescription = null,
                        tint = PadGuardColors.PrimaryBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text("受管控水印", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            "在孩子屏幕上显示监护提示",
                            fontSize = 12.sp,
                            color = PadGuardColors.TextSecondary
                        )
                    }
                }
                Switch(checked = enabled, onCheckedChange = { onApply(it, draft) }, enabled = !busy)
            }

            OutlinedTextField(
                value = draft,
                onValueChange = { if (it.length <= 30) draft = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                label = { Text("水印文案（留空用默认）") },
                placeholder = { Text("受管控设备") }
            )

            // 系统限制说明：把"做不到"这件事显式告知，避免家长反复尝试
            Surface(
                color = PadGuardColors.Amber.copy(alpha = 0.10f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = "录屏时系统显示的红色录制指示器由 Android 系统绘制，" +
                        "属于系统隐私提示，任何应用都无法关闭。本水印是本应用自己的提示，" +
                        "可自定义文案、可随时关闭。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = PadGuardColors.TextSecondary,
                    modifier = Modifier.padding(10.dp)
                )
            }

            Button(
                onClick = { onApply(true, draft) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && (dirty || !enabled),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PadGuardColors.PrimaryBlue)
            ) {
                Text(if (busy) "下发中…" else "应用水印")
            }
        }
    }
}
