package com.padguard.parent.presentation.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.padguard.parent.presentation.ui.components.SoftCard
import com.padguard.parent.presentation.ui.theme.PadGuardColors

/**
 * 整机管控 暂停 / 恢复 主开关卡片（设备详情页）。
 *
 * ## 这张卡解决的是什么
 * 之前「取消应用管控」只清掉应用名单，设备仍被锁屏浮层 + 设置/应用拦截 + DO 权限回收钉死，
 * 家长进不了系统设置；而国产 ROM 切换「悬浮窗」等权限会强制重启应用，
 * 重启后无障碍、录屏授权被系统清空，表现为"权限全掉了"。
 * 这里提供一把能真正**释放 / 恢复整机**的总闸：
 *
 * - 暂停（active=false）：锁屏浮层收起、设置/应用拦截解除、DO 设备归还权限、不钉 Kiosk，
 *   设备回到完全可用，家长/操作员可自由进入系统设置；
 * - 恢复（active=true）：重新生效全部管控（仍处于锁定态时重铺锁屏等）。
 *
 * 下发链路：SET_MANAGEMENT_ACTIVE 指令 → 孩子端 [GuardMasterSwitch] 统一决策。
 * 开关态为跨页面共享（详情页与首页读同一份），默认 true（与孩子端默认一致）。
 */
@Composable
fun ManagementActiveCard(
    active: Boolean,
    busy: Boolean = false,
    onToggle: (Boolean) -> Unit
) {
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
                        imageVector = Icons.Filled.Shield,
                        contentDescription = null,
                        tint = PadGuardColors.PrimaryBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text("整机管控", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            if (active) "管控生效中" else "已暂停 · 设备完全可用",
                            fontSize = 12.sp,
                            color = PadGuardColors.TextSecondary
                        )
                    }
                }
                Switch(checked = active, onCheckedChange = onToggle, enabled = !busy)
            }

            // 把"暂停到底会怎样"显式写进卡片，避免家长误以为只是关掉某个小功能。
            Surface(
                color = PadGuardColors.Amber.copy(alpha = 0.10f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = "暂停后：锁屏浮层收起、设置/应用拦截解除、设备回到完全可用" +
                        "（可自由进入系统设置）。恢复后重新生效全部管控。" +
                        "用于「取消管控后进不了设置」「切换权限被系统强重应用导致权限掉失」等场景。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = PadGuardColors.TextSecondary,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
    }
}
