package com.padguard.server.common

import com.padguard.server.dto.EventPacket
import com.padguard.server.dto.LogItem

/**
 * 孩子端上行报文的**结构边界**校验（事件 / 日志两个入口共用）。
 *
 * ## 为什么必须有
 * 上行是设备主动写入，服务端此前只限制了"一批多少条"，没有限制单条字段长度与 payload 大小。
 * 一条 `type` 超过 `device_events.type VARCHAR(32)` 的事件、或一个几十 MB 的 detail，
 * 会让 SQL 抛非业务异常（500）；而孩子端只把 `BizError` 当"永久拒绝"丢弃，
 * 500 会原样留在队头重试 —— **一条毒数据就能把该设备后续所有告警/日志永久堵死**。
 *
 * 这里只做与数据库列宽/内存占用直接相关的结构校验，**不做业务白名单**：
 * 孩子端新增 RiskType/LogType 时不应因为服务端还没升级就整批丢失。
 * 校验失败必须抛 [BizException]（HTTP 400 / 孩子端 BizError），端上才会丢弃坏批次继续流动。
 */
object UplinkValidation {

    const val MAX_ID_LENGTH = 64
    const val MAX_TYPE_LENGTH = 32
    const val MAX_LEVEL_LENGTH = 16
    const val MAX_DETAIL_BYTES = 16 * 1024
    const val MAX_PAYLOAD_BYTES = 64 * 1024

    /** eventId / logId：UUID、`evt_...`、`log-...` 均合法；禁止空白/路径分隔符/超长 */
    private val ID_PATTERN = Regex("^[A-Za-z0-9_.:-]{1,$MAX_ID_LENGTH}$")

    /** 类型：与 `VARCHAR(32)` 对齐，只允许大写下划线枚举名（两端枚举均为此形态） */
    private val TYPE_PATTERN = Regex("^[A-Z][A-Z0-9_]{0,${MAX_TYPE_LENGTH - 1}}$")

    fun eventError(ev: EventPacket, detailBytes: Int): String? = when {
        !ID_PATTERN.matches(ev.eventId) -> "eventId 非法（1~$MAX_ID_LENGTH 位字母数字/_-:.）"
        !TYPE_PATTERN.matches(ev.type) -> "event type 非法（1~$MAX_TYPE_LENGTH 位大写字母/数字/下划线）"
        ev.level.isBlank() || ev.level.length > MAX_LEVEL_LENGTH -> "event level 非法"
        ev.timestamp <= 0 -> "event timestamp 非法"
        detailBytes > MAX_DETAIL_BYTES -> "event detail 过大（上限 ${MAX_DETAIL_BYTES}B）"
        else -> null
    }

    fun logError(item: LogItem, payloadBytes: Int): String? = when {
        !ID_PATTERN.matches(item.logId) -> "logId 非法（1~$MAX_ID_LENGTH 位字母数字/_-:.）"
        item.type != null && !TYPE_PATTERN.matches(item.type) ->
            "log type 非法（1~$MAX_TYPE_LENGTH 位大写字母/数字/下划线）"
        item.timestamp != null && item.timestamp <= 0 -> "log timestamp 非法"
        payloadBytes > MAX_PAYLOAD_BYTES -> "log payload 过大（上限 ${MAX_PAYLOAD_BYTES}B）"
        else -> null
    }
}
