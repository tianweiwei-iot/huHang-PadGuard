package com.padguard.presentation.ui.device

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.padguard.domain.repository.AgeBand
import com.padguard.domain.repository.AgeBandDefault
import com.padguard.domain.repository.MinorMode
import com.padguard.presentation.ui.components.SoftCard
import com.padguard.presentation.ui.theme.PadGuardColors

/**
 * 未成年人模式卡片（P1 合规底座）。
 *
 * ## 界面要回答的三个问题
 * 家长在这张卡上停留不超过 10 秒，所以必须一眼看到：
 * 1. **开没开** —— 顶部大开关，颜色即状态；
 * 2. **开了会怎样** —— 选中龄档后立刻展示"每天 X 分钟 / 连续 Y 分钟休息 / 宵禁 A–B"；
 *    这是刻意做的：**先展示后果再下发**，避免家长点了开启才发现孩子今晚 20:00 就被锁；
 * 3. **现在有没有例外** —— 家长临时豁免的剩余时间显式列出，且注明"时长仍生效"。
 *
 * ## 为什么"临时豁免"要单独一个入口
 * 宵禁是硬的，但现实里确实有"今晚特殊"的场景。把它放进卡片而不是塞进锁屏指令列表，
 * 是为了让家长清楚知道：**豁免只解决宵禁，不解决额度**。
 * 若做成"再给 30 分钟"的通用按钮，孩子就能靠反复申请把每日额度顶穿。
 */
@Composable
fun MinorModeCard(
    minorMode: MinorMode?,
    ageBandDefaults: Map<AgeBand, AgeBandDefault>,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onSelectBand: (AgeBand) -> Unit,
    onToggleCurfew: (Boolean) -> Unit,
    onGrantExemption: (Int) -> Unit
) {
    val mode = minorMode ?: MinorMode()
    val enabled = mode.enabled
    // 预览档位：开启时就是当前档；未开启时用当前档预览"开启后会怎样"，
    // 让家长在按开关之前就能看到后果。
    var previewBand by remember(mode.ageBand) { mutableStateOf(mode.ageBand) }
    val band = if (enabled) mode.ageBand else previewBand
    val def = ageBandDefaults[band]

    // 关闭是合规敏感操作：一旦关闭，设备上的时长、护眼、宵禁限制会一并解除。
    // 这里要求家长二次确认，避免误触（大开关就在页面顶部，滑动误触概率不低）。
    var pendingClose by remember { mutableStateOf(false) }
    if (pendingClose) {
        AlertDialog(
            onDismissRequest = { pendingClose = false },
            title = { Text("关闭未成年人模式？") },
            text = {
                Text(
                    "关闭后，分龄时长、护眼休息与夜间宵禁都会一并解除，" +
                        "设备将回到你自己配置的管控规则。此操作由你本人确认后才会下发。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingClose = false
                    onToggle(false)
                }) { Text("确认关闭") }
            },
            dismissButton = {
                TextButton(onClick = { pendingClose = false }) { Text("取消") }
            }
        )
    }

    val dailyMinutes = mode.dailyLimitMinutes.takeIf { it > 0 } ?: def?.dailyLimitMinutes ?: 60
    val continuousMinutes = mode.continuousMinutes.takeIf { it > 0 } ?: def?.continuousMinutes ?: 30
    val curfewStart = def?.curfewStart ?: mode.curfewStart
    val curfewEnd = def?.curfewEnd ?: mode.curfewEnd

    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------- 标题行 + 总开关 ----------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            if (enabled) PadGuardColors.TintContainers[1] else PadGuardColors.SectionBg,
                            RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.ChildCare,
                        contentDescription = null,
                        tint = if (enabled) PadGuardColors.AccentPurple else PadGuardColors.TextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "未成年人模式",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = PadGuardColors.TextPrimary
                    )
                    Text(
                        if (enabled) "分龄管控 + 夜间宵禁已生效" else "按年龄段自动套用合规基线",
                        fontSize = 12.sp,
                        color = PadGuardColors.TextSecondary
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { if (!busy) onToggle(it) },
                    enabled = !busy,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = PadGuardColors.AccentPurple
                    )
                )
            }

            // ---------- 龄档选择 ----------
            Text("孩子年龄段", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = PadGuardColors.TextSecondary)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AgeBand.entries.forEach { item ->
                    AgeBandRow(
                        band = item,
                        selected = item == band,
                        hint = item.hint,
                        onClick = {
                            if (busy) return@AgeBandRow
                            if (enabled) onSelectBand(item) else previewBand = item
                        }
                    )
                }
            }

            // ---------- 开启后的实际后果 ----------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PadGuardColors.SectionBg, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    if (enabled) "当前生效" else "开启后生效",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = PadGuardColors.TextSecondary
                )
                ConsequenceRow(
                    icon = Icons.Default.Timer,
                    text = "每天可用 $dailyMinutes 分钟"
                )
                ConsequenceRow(
                    icon = Icons.Default.Timer,
                    text = "连续使用 $continuousMinutes 分钟提醒休息"
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Bedtime,
                        contentDescription = null,
                        tint = PadGuardColors.TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "夜间宵禁 $curfewStart–$curfewEnd",
                        fontSize = 13.sp,
                        color = PadGuardColors.TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = mode.curfewEnabled,
                        onCheckedChange = { if (!busy) onToggleCurfew(it) },
                        enabled = !busy
                    )
                }
            }

            // ---------- 家长临时豁免 ----------
            if (enabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60).forEach { minutes ->
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable(enabled = !busy) { onGrantExemption(minutes) },
                            shape = RoundedCornerShape(10.dp),
                            color = Color.White,
                            border = BorderStroke(1.dp, PadGuardColors.SectionBg)
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "豁免${minutes}分",
                                    fontSize = 12.sp,
                                    color = PadGuardColors.PrimaryBlue
                                )
                            }
                        }
                    }
                }
                Text(
                    "豁免仅解除宵禁与护眼休息，每日总时长仍然生效。",
                    fontSize = 11.sp,
                    color = PadGuardColors.TextSecondary
                )
            }

            if (mode.exemptActive) {
                val leftMinutes = ((mode.parentExemptUntil - System.currentTimeMillis()) / 60_000L).toInt() + 1
                Text(
                    "家长豁免生效中，剩余约 ${leftMinutes.coerceAtLeast(1)} 分钟",
                    fontSize = 12.sp,
                    color = PadGuardColors.PrimaryBlue
                )
            }
        }
    }
}

@Composable
private fun AgeBandRow(
    band: AgeBand,
    selected: Boolean,
    hint: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) PadGuardColors.TintContainers[1] else Color.White,
        border = if (selected) null else BorderStroke(1.dp, PadGuardColors.SectionBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                band.label,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) PadGuardColors.AccentPurple else PadGuardColors.TextPrimary
            )
            Spacer(Modifier.width(10.dp))
            Text(
                hint,
                fontSize = 11.sp,
                color = PadGuardColors.TextSecondary,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Icon(
                    Icons.Default.ChildCare,
                    contentDescription = null,
                    tint = PadGuardColors.AccentPurple,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun ConsequenceRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = PadGuardColors.TextSecondary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, color = PadGuardColors.TextPrimary)
    }
}
