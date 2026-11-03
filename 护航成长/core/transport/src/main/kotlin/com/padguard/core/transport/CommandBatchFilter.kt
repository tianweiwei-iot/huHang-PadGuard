package com.padguard.core.transport

import com.padguard.core.data.model.CommandType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 指令批次的「未知类型过滤」结果。
 *
 * @property element 过滤后的批次元素（形态与入参一致：数组 → 数组，对象 → 对象）
 * @property droppedTypes 被剔除的未知 `type` 值（按出现顺序），由调用方在运行时记日志
 */
internal class CommandBatchFilterResult(
    val element: JsonElement,
    val droppedTypes: List<String>
)

/**
 * 指令批次的「未知类型过滤」。
 *
 * ## 为什么需要它
 * [CommandType] 是严格枚举：服务端先升级、孩子端还没跟上时，一条新类型指令会让
 * kotlinx.serialization 在**批级别**抛错 —— 轮询通道一次拉回的整批指令全部丢弃，
 * 锁屏、解锁等所有既有指令跟着一起瘫痪，故障面远超"不支持那一条"。
 *
 * 解法：反序列化前按 `type` 字符串预过滤，删掉孩子端不认识的指令对象，
 * 保住同批次里的其余指令。被删的指令在服务端保持 PENDING 至过期（孩子端拿不到
 * msgId 无法回执），家长端表现为"设备未响应"—— 这正是老版本设备的真实语义。
 *
 * ## 设计约束
 * 本函数**必须保持纯函数**（不碰 android.util.Log）：core/transport 的单测跑在
 * 本地 JVM，Logger 底层的 android.util.Log 未被 mock，一调用就会抛
 * "not mocked"。剔除明细通过 [CommandBatchFilterResult.droppedTypes] 交回调用方记日志。
 *
 * ## 边界
 * - 缺 `type` 字段、type 非字符串的对象不在此处理，保持原样交给反序列化报错
 *   （那是协议被破坏的情形，静默吞掉反而掩盖问题）；
 * - 两种批次形态都覆盖：裸数组（旧轮询返回）与 `{policyVersion, commands}` 对象。
 */
internal fun filterKnownCommandTypes(element: JsonElement): CommandBatchFilterResult {
    val known = CommandType.entries.mapTo(mutableSetOf()) { it.name }
    val dropped = mutableListOf<String>()

    fun isKnownType(obj: JsonObject): Boolean {
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: return true
        if (type in known) return true
        dropped += type
        return false
    }

    val filtered: JsonElement = when (element) {
        is JsonArray -> JsonArray(element.filterIsInstance<JsonObject>().filter(::isKnownType))
        is JsonObject -> {
            val commands = element["commands"] as? JsonArray
                ?: return CommandBatchFilterResult(element, dropped)
            JsonObject(
                element.toMutableMap().apply {
                    put("commands", JsonArray(commands.filterIsInstance<JsonObject>().filter(::isKnownType)))
                }
            )
        }
        else -> element
    }
    return CommandBatchFilterResult(filtered, dropped)
}
