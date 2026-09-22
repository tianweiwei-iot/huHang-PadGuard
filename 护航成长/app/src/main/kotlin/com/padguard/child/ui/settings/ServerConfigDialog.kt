package com.padguard.child.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 「高级设置」弹窗：拆分编辑服务器 地址 / 端口 / 接口前缀，保存时拼回完整 baseUrl。
 *
 * 完整地址实时预览，降低手填出错概率（USB 直连场景尤其有用）。
 * 被管控端在「我的 → 更多 → 高级设置」与「绑定欢迎页」共用本弹窗，
 * 仅把完整 URL 交给调用方（[com.padguard.core.transport.TransportSettings.setBaseUrl]），
 * 不改动传输层既有的动态 baseUrl 机制。
 */
@Composable
fun ServerConfigDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    val cfg = remember(initial) { parseServerConfig(initial) }
    var host by remember { mutableStateOf(cfg.host) }
    var port by remember { mutableStateOf(cfg.port) }
    var path by remember { mutableStateOf(cfg.path) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val built = buildServerConfig(host, port, path)
                if (built == null) {
                    error = "请填写有效的服务器地址与端口（1–65535）"
                    return@TextButton
                }
                onSave(built)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("高级设置 · 服务器配置") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim(); error = null },
                    label = { Text("服务器地址") },
                    placeholder = { Text("如 192.168.1.10") },
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
                    placeholder = { Text("如 /api/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
                val preview = buildServerConfig(host, port, path)
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
                Text(
                    text = "默认已填好当前地址。若连不上，请改为「平板用 USB 线直连电脑」时电脑显示的地址（见安装说明）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    )
}

/** 把完整 baseUrl 解析成 地址 / 端口 / 前缀，供高级设置弹窗回显。 */
private fun parseServerConfig(raw: String): ServerConfig {
    val noScheme = raw.trim().removePrefix("https://").removePrefix("http://")
    val slash = noScheme.indexOf('/')
    val authority = if (slash >= 0) noScheme.substring(0, slash) else noScheme
    val pathPart = if (slash >= 0) noScheme.substring(slash) else "/"
    val colon = authority.indexOf(':')
    val host = if (colon >= 0) authority.substring(0, colon) else authority
    val port = if (colon >= 0) authority.substring(colon + 1) else "8090"
    return ServerConfig(host = host, port = port, path = pathPart)
}

/** 由 地址 / 端口 / 前缀 拼回完整 baseUrl；任一不合法返回 null。 */
private fun buildServerConfig(host: String, port: String, path: String): String? {
    val h = host.trim()
    if (h.isBlank()) return null
    val p = port.trim().toIntOrNull() ?: return null
    if (p <= 0 || p > 65535) return null
    val cleanPath = path.trim().trimStart('/').trimEnd('/').let { if (it.isBlank()) "" else "/$it" }
    return "http://$h:$p$cleanPath/"
}

/** 高级设置弹窗用的服务器配置拆分。 */
private data class ServerConfig(
    val host: String = "",
    val port: String = "8090",
    val path: String = "/api/v1"
)
