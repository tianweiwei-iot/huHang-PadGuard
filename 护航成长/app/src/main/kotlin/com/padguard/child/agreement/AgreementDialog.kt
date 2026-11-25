package com.padguard.child.agreement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 使用授权协议弹窗（说明书 §5）。
 *
 * 触发时机：绑定成功后、自动授予权限前，模态展示，不可后台跳过（点外部不关闭）。
 * 同意 → 调 [AgreementViewModel.agree]（自动授予全部权限）；
 * 暂不同意 → [AgreementViewModel.decline]，进入受限预览模式。
 */
@Composable
fun AgreementDialog(
    onAgree: () -> Unit,
    onDecline: () -> Unit,
    granting: Boolean = false
) {
    var showPolicy by remember { mutableStateOf(false) }
    AlertDialog(
        // 不可后台跳过：协议是合规前置条件，点外部不关闭
        onDismissRequest = { },
        title = {
            Text(
                text = "使用授权协议",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp)
            ) {
                // 同一弹窗内切换查看，而不是跳外链：
                // 首次授权发生在绑定瞬间，此时未必有可用的网络，外链打不开等于没有隐私政策。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !showPolicy,
                        onClick = { showPolicy = false },
                        label = { Text("授权协议", style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = showPolicy,
                        onClick = { showPolicy = true },
                        label = { Text("隐私政策", style = MaterialTheme.typography.labelSmall) }
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (showPolicy) PRIVACY_POLICY_TEXT else AGREEMENT_TEXT,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Start
                )
            }
        },
        confirmButton = {
            Button(onClick = onAgree, enabled = !granting) {
                Text(text = if (granting) "正在授权…" else "同意并授权")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDecline, enabled = !granting) {
                Text(text = "暂不同意")
            }
        }
    )
}

/**
 * 入口门禁承载：仅当「已绑定但未同意」时由 [com.padguard.child.MainActivity.EntryRouter] 挂载。
 * [agreed] 翻转后由上层停止挂载，弹窗随之收起。
 */
@Composable
fun AgreementDialogHost() {
    val vm: AgreementViewModel = hiltViewModel()
    val granting by vm.granting.collectAsStateWithLifecycle()
    AgreementDialog(
        onAgree = vm::agree,
        onDecline = vm::decline,
        granting = granting
    )
}

/**
 * 协议正文（v2.0，随包内置完整版，无外部链接）。
 *
 * 法务整改（2026-11-25）：旧版对处理者身份与运营方接触数据的能力作了与事实相反的陈述，
 * 与官方托管服务和运营方管理后台的客服/运维访问能力直接冲突，属 PIPL 第 17 条下的不实告知。
 * v2.0 与根目录《隐私政策.md》《用户协议.md》保持同一口径：
 * 区分两种部署形态、如实披露 RBAC+审计下的接触、写明保存期限与撤回路径。
 *
 * 同时必须如实写明局域网明文传输风险（不写属于隐瞒），以及软管控的能力边界。
 */
internal const val AGREEMENT_TEXT = """
护航成长 · 使用授权协议（v2.0，2026-11-25 生效）

您是本设备的监护人。点击「同意并开始授权」，即表示您已阅读并同意本协议与《隐私政策》全文，
并确认有权代表本设备的未成年使用者作出该同意。

一、启用前提
本应用仅在您明示同意后才启用采集与管控能力。未同意前，不采集位置、不读取应用使用情况、
不执行锁屏或限额、不连接服务端守护通道。您可随时撤回同意（见《隐私政策》第八节）。

二、授权范围
• 设备信息：型号、系统版本、序列号（脱敏）、存储、电池、网络状态；
• 位置信息：GPS / 基站 / WiFi 定位与历史轨迹，仅在设备前台使用时采集；
• 应用使用统计与管控：安装、卸载、冻结、隐藏、时长与时段限制；
• 摄像头与麦克风：远程截屏 / 录屏 / 实时画面、环境音监听（需家长实时发起，孩子端有状态提示）；
• 存储读写：日志、截图、下发文件；
• 远程控制：锁屏、重启、关机、丢失模式、恢复出厂（需家长二次授权）；
• 网络与通知：消息推送、公告广播。

三、明确不采集
本应用不读取短信内容、通话记录、通讯录、输入内容与聊天记录；
不含广告、统计或崩溃上报类 SDK。

四、数据去向与处理者
本产品提供两种部署形态：
• 官方托管：数据存放在我们运营的中国境内云服务器，我们是个人信息处理者；
• 家长自建：数据只存在家长自管的电脑或服务器，家长是处理者，我们仅分发软件。
为保障客服与运维，我们的授权工作人员可能在严格的角色权限控制与操作审计下，
接触处理工单、排查故障所必需的账号信息、设备状态记录与使用数据，且不用于广告或用户画像。
我们不会向第三方出售您的个人信息。

五、能力边界
本应用为家长监护工具，提供软管控与硬管控两档。软管控无法绝对阻止卸载、强行停止、
修改系统时间、进入安全模式或恢复出厂；我们不对"绝对不可绕过"作出承诺。
请仅在合法监护目的内使用，不得用于对成年家庭成员的隐蔽监控。

六、传输安全
公网部署必须使用 HTTPS；家庭局域网自建当前可能以明文传输，
同网段具备嗅探能力的人可能截获截图、实时画面等内容，请仅在可信网络中使用。

七、未成年人保护
不满十四周岁儿童的信息在取得监护人单独同意后处理；
我们依法开展个人信息保护影响评估，相关报告与处理记录至少保存三年。

客服：support@padguard.com。完整规则见《隐私政策》。
"""

/**
 * 隐私政策正文（v2.0，随包内置即为完整版，无外部链接）。
 *
 * 为什么必须内置而不是放外链：首次授权发生在绑定瞬间，此时平板可能刚连上家庭网络、
 * 也可能尚未联网，外链打不开就等于没有隐私政策 —— 而"未向用户明示处理规则"
 * 正是应用商店审核与《儿童个人信息网络保护规定》的红线。
 */
internal const val PRIVACY_POLICY_TEXT = """
护航成长 · 隐私政策（v2.0 全文，2026-11-25 生效）

一、处理者身份
本产品由应用市场开发者信息所载主体提供，客服 support@padguard.com。
• 官方托管（家长端默认接入）：数据存放于我们运营的中国境内云服务器，我们是个人信息处理者；
• 家长自建部署：数据存放在家长自管的电脑或服务器，家长是处理者，我们仅分发软件。
客服与运维需要时，授权工作人员可能在角色权限控制与操作审计下接触必要信息，
以最小必要为限，不用于广告或用户画像。

二、收集范围
设备信息（型号 / 系统版本 / 脱敏序列号 / 电池 / 网络 / 应用列表）；
位置（GPS / 基站 / WiFi 与轨迹，仅前台使用时采集）；应用使用时长与起止；
网页域名与拦截记录；截图 / 录屏 / 实时画面 / 环境音 / 拍照（均需家长实时发起，孩子端有状态提示）；
Root 与卸载尝试等安全告警。
不收集：短信内容、通话记录、通讯录、输入与聊天文本、广告画像数据。

三、使用目的
仅用于家长对未成年人设备的监护：时长时段管控、应用与网址管控、位置守护、安全告警。
不用于广告、画像或其他与守护无关的用途。

四、第三方 SDK 与共享
孩子端未集成广告、统计或崩溃上报 SDK。家长端集成高德地图 SDK（地图展示与地址反查）
与微信开放平台 SDK（订阅支付），分别遵循其隐私政策。
除上述情形外，不向第三方出售、共享或披露个人信息。

五、保存期限
使用日志与位置轨迹默认保留 30 天，告警与安全事件默认保留 90 天，到期自动清理；
家长可随时导出或删除。注销后业务数据物理删除、账号匿名化，订单与审计记录按法定期限保留。

六、安全措施
访问需身份凭证；服务端下发的每条指令都带 HMAC-SHA256 签名，孩子端校验不符即丢弃；
密码哈希存储；公网必须 HTTPS，局域网明文传输风险见第九节。

七、未成年人保护
首次启用须监护人明示同意；只采集必需信息；不满十四周岁儿童信息在取得监护人单独同意后处理；
依法开展个人信息保护影响评估，记录保存不少于三年。

八、您的权利
家长可通过家长端「我的 → 数据权利」自助导出、撤回采集同意、申请注销；
也可通过 support@padguard.com 申请查阅、更正、删除。
撤回同意后孩子端离线持久化该状态并停止采集，重新同意需由家长端操作。
我们将在 15 个工作日内响应。

九、传输与网络
公网部署必须使用 HTTPS；家庭局域网自建当前可能明文传输，
同网段嗅探者可截获截图等内容，请仅在可信网络中使用。

十、更新
本协议升版时会在 App 内重新征求明示同意；未同意前不启用采集与管控。
"""
