package com.padguard.parent.presentation.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.padguard.parent.presentation.util.TimeFormat

/**
 * 「当前显示的是旧数据」统一角标（R-P3-1）。
 *
 * ## 为什么需要它
 * 各业务页的刷新失败处理只做了两件事之一：
 * ① 整页替换成错误页 —— 手里明明还有可用的旧数据，却被一起丢掉；
 * ② 只在 Snackbar 里闪一句 —— 提示消失后页面照旧展示数字，**看起来完全正常**。
 * ②是最危险的一种：家长看到"今日已用 42 分钟"，而这份数字可能是三小时前拉到的，
 * 孩子此刻还在用平板。家长据此决定"要不要收平板"，结论就建立在过期事实上。
 *
 * 所以这里统一成一条**常驻**的窄条：明说"刷新失败" + 明说"数据是哪一刻的" + 可重试。
 *
 * ## 用法约定
 * - 页面**已有数据**（`hasData` / 列表非空 / `device != null`）且刷新失败时展示本组件；
 * - 页面**没有任何数据**时不要用它，应展示整页错误页（此时不存在"旧数据"这回事，
 *   说"以下是旧数据"是撒谎）。
 *
 * @param message       失败原因（来自各 UiState 的 error 字段），空串时只显示时间信息
 * @param lastSuccessAt 最近一次成功刷新的时刻（墙钟毫秒）；null 表示本次会话还没有成功过，
 *                      此时降级为纯错误提示，不编造"上次更新"时间
 * @param onRetry       重试回调；为 null 时不显示重试按钮（如页面没有可重复触发的加载入口）
 */
@Composable
fun StaleDataHint(
    message: String?,
    lastSuccessAt: Long?,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) {
    val detail = message?.takeIf { it.isNotBlank() }
    val text = when {
        lastSuccessAt != null && detail != null ->
            "刷新失败，当前显示 ${TimeFormat.describeStamp(lastSuccessAt)} 的数据（$detail）"
        lastSuccessAt != null ->
            "刷新失败，当前显示 ${TimeFormat.describeStamp(lastSuccessAt)} 的数据"
        detail != null -> "刷新失败：$detail"
        else -> "刷新失败"
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (onRetry != null) {
                TextButton(onClick = onRetry) { Text("重试") }
            }
        }
    }
}
