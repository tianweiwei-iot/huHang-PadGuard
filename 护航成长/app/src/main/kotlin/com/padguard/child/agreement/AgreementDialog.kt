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
 * 协议正文。
 *
 * 必须与 AndroidManifest 实际声明的权限一致：曾经这里写着"读取短信/通话记录"，
 * 而权限早已按合规要求移除 —— 超范围声明比不声明更糟，属于对用户的虚假陈述。
 *
 * 同样地，原文写"所有数据经加密上传"，而家庭部署实际是局域网明文 HTTP，
 * 也是虚假陈述。已改为如实说明，并把使用边界写清楚。
 */
internal const val AGREEMENT_TEXT = """
为保障家长（管控端）对设备的远程守护能力，您同意后，本应用将获取并使用被管控端的以下权限：

• 设备信息：型号、系统版本、序列号（脱敏）、存储、电池、网络状态；
• 位置信息：GPS / 基站 / WiFi 定位与历史轨迹；
• 应用使用统计与管控：安装、卸载、冻结、隐藏、时长与时段限制；
• 摄像头与麦克风：远程截屏 / 录屏 / 实时画面、环境音监听（需家长实时发起）；
• 存储读写：日志、截图、下发文件；
• 远程控制：锁屏、重启、关机、丢失模式、恢复出厂（需家长二次授权）；
• 网络与通知：消息推送、公告广播。

本应用**不**读取短信内容、通话记录与通讯录。

上述数据仅上传至家长自行部署的护航服务端，且仅与该设备绑定的管控端可查看与操作。
数据不经由本应用的开发方服务器，开发方无法接触任何数据。

传输说明：当前版本在家庭局域网内以明文方式传输。请勿在不可信的公共网络环境下部署使用。

设备将进入未成年人守护模式，部分系统能力（恢复出厂、USB 调试、未知来源安装）将被限制，需管控端授权方可解除。

详细的信息处理规则见《隐私政策》。
"""

/**
 * 隐私政策正文（完整版见发布包中的《隐私政策》）。
 *
 * 为什么必须内置而不是放外链：首次授权发生在绑定瞬间，此时平板可能刚连上家庭网络、
 * 也可能尚未联网，外链打不开就等于没有隐私政策 —— 而"未向用户明示处理规则"
 * 正是应用商店审核与《儿童个人信息网络保护规定》的红线。
 *
 * 关键事实必须如实写明：
 * - 服务端由家长自建，开发方不接触数据（这是本系统与云端管控产品的本质区别）；
 * - 当前版本局域网内明文传输（不写会被认定为隐瞒，写了才算尽到告知义务）。
 */
internal const val PRIVACY_POLICY_TEXT = """
护航成长 · 隐私政策（摘要）

一、谁在处理你的信息
本系统由「孩子端 App」「家长端 App」和「服务端」三部分组成，服务端由家长自行部署在自己的电脑上，所有数据只存在那台电脑里。
本应用的开发方不运营任何服务器，不接收、不存储、不接触任何数据。就本系统产生的数据而言，个人信息处理者是家长本人，而不是开发方。

二、我们收集什么
• 设备信息：型号、系统版本、序列号（脱敏）、电池与网络状态、已安装应用列表；
• 位置信息：GPS / 基站 / WiFi 定位与历史轨迹；
• 应用使用情况：各应用的使用时长与起止时间；
• 网页访问：访问的域名、被拦截的记录；
• 图像与声音：截图、录屏、实时画面、环境音、拍照 —— 均需家长在家长端实时主动发起，App 不会自行采集；
• 安全告警：Root 状态、卸载尝试等。

我们不收集：短信内容、通话记录、通讯录、输入内容与聊天记录；不含任何广告或统计 SDK。

三、为什么收集
仅用于实现家长对未成年人设备的守护：时长与时段管控、应用与网址管控、位置守护、安全告警。不用于广告、画像或任何与守护无关的用途。

四、存储与期限
数据保存在家长自建服务端的本地数据库中，保留多久由家长决定。家长可随时删除，卸载服务端即视为全部销毁。

五、未成年人保护
• 首次启动必须经监护人明示同意后方可启用守护能力；
• 只采集实现守护所必需的信息；
• 数据不出家长自有设备；
• 家长可随时查看、导出、删除孩子的全部数据。

六、安全措施
• 所有访问均需身份凭证，无凭证会被拒绝；
• 服务端下发的每条指令都带 HMAC-SHA256 签名，孩子端校验不符即丢弃，防止伪造指令；
• 家长密码哈希存储，不保存明文；
• 实时控制通道已启用口令认证，未授权设备无法接入。

七、如实告知当前限制
当前版本在家庭局域网内以明文方式传输。在同一局域网内，具备网络嗅探能力的人可能截获截图、实时画面等内容。请仅在可信的家庭网络中部署使用，避免在公共 WiFi 或不可信的共享网络中使用。

八、你的权利
查阅、复制导出、更正、删除数据，撤回同意，注销账号。行使方式：家长端可直接查看与删除；使用发布包的备份功能可导出；停止服务端并删除数据目录即完成全部销毁。

九、第三方共享
不向任何第三方出售、共享或披露。

十、更新
本政策更新时会同步更新版本号。若涉及收集范围的实质变化，会重新征得同意。

完整版见发布包中的《隐私政策》。
"""
