package com.padguard.server.security

/**
 * 指令签名的 canonical payload 协议（服务端侧权威实现）。
 *
 * ## 为什么单独抽出来
 * 这个格式是**跨端协议**：终端 `com.padguard.core.data.model.Command.canonicalJson` 必须
 * 与这里逐字符一致，否则 HMAC 校验失败，所有指令（含远程锁屏）被终端判为伪造并拒绝执行。
 * 放在 `CommandService` 的私有方法里时，它既不可测也不可被别的签发路径复用 ——
 * 一旦有人新增一条下发通道自己拼串，就会静默偏离协议。
 *
 * ## 格式
 * `{"k1":"v1","k2":"v2"}`，键按字典序升序；键与值都做标准 JSON 字符串转义。
 * 空 payload 为 `{}`（不是空串）。
 *
 * ## 为什么必须转义
 * 早期两端都是直接 `"${key}":"${value}"` 拼接、不转义。当值里出现 `"` 或 `\` 时，
 * 不同的 payload 可以拼出**同一个** canonical 串（格式歧义），签名就不再是 payload 的
 * 单射函数——攻击者理论上可以让两个语义不同的 payload 共享一个合法签名。
 * 目前 payload 值来自家长端 UI 与固定指令类型，尚未出现真实撞车，
 * 但"靠输入恰好干净"不是安全边界，属于必须修的协议缺陷。
 *
 * ## 迁移兼容
 * 服务端与 APK 无法同时升级：任何一侧先改格式，另一侧算出的串就对不上，
 * 表现为"指令全部被拒"，而家长端只看到"设备不响应"。因此终端保留旧格式回退
 * （`Command.signingPayloadLegacy`），验签失败时按旧格式再试一次。
 * 新格式**只在值真的含特殊字符时才与旧格式不同**，所以绝大多数指令在迁移期两侧都能过。
 */
object CommandSigning {

    /** 规范化 payload；[payload] 为 null 或空时返回 `{}` */
    fun canonical(payload: Map<String, String>?): String {
        if (payload.isNullOrEmpty()) return "{}"
        return payload.entries.sortedBy { it.key }
            .joinToString(",", "{", "}") { "\"${escape(it.key)}\":\"${escape(it.value)}\"" }
    }

    /**
     * 转义规则，必须与终端 `Command.escape` 逐字符一致：
     * - `\` → `\\`
     * - `"` → `\"`
     * - 控制字符（< 0x20）→ `\u00xx`，十六进制**小写**、补足四位
     *
     * 不转义 `/`（JSON 允许），不转义非 ASCII（直接输出 UTF-8，两端 Kotlin 均按 UTF-8 编码字节）。
     * 这两点若各自"顺手加了转义"，就会造成两端不一致 —— 见类注释。
     */
    fun escape(raw: String): String {
        // 绝大多数值的转义结果就是自身，先做一次扫描避免无谓的 StringBuilder 分配
        if (raw.none { it == '\\' || it == '"' || it.code < 0x20 }) return raw
        return buildString(raw.length + 8) {
            raw.forEach { ch ->
                when {
                    ch == '\\' -> append("\\\\")
                    ch == '"' -> append("\\\"")
                    ch.code < 0x20 -> append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                    else -> append(ch)
                }
            }
        }
    }
}
