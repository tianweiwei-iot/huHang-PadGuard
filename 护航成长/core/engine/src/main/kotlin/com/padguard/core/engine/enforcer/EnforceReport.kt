package com.padguard.core.engine.enforcer

import com.padguard.core.engine.admin.OpResult

/**
 * 策略下发结果汇总。
 *
 * 设计动机：管控类产品最怕"下发成功但没生效"。
 * 服务端只要收到 200 就认为策略已落地，可实际上终端可能因为权限降级、
 * 厂商 ROM 限制、目标应用未安装等原因一条都没执行。
 *
 * 因此每次 apply 都产出这份报告，随心跳/事件上报服务端，
 * 让管控端能明确看到"这台设备有 3 项策略未生效，原因是没有 Device Owner"。
 */
data class EnforceReport(
    val applied: MutableList<String> = mutableListOf(),
    /** 权限/系统版本不支持导致的跳过 —— 预期内降级 */
    val unsupported: MutableList<String> = mutableListOf(),
    /** 真正的执行失败 —— 需要告警 */
    val failed: MutableList<String> = mutableListOf()
) {

    fun record(result: OpResult) {
        when (result) {
            is OpResult.Ok -> Unit
            is OpResult.Unsupported -> unsupported += "${result.op}: ${result.reason}"
            is OpResult.Failed -> failed += "${result.op}: ${result.reason}"
        }
    }

    /** 带名字记录，成功项也留痕，便于本地排查"策略到底有没有下过去" */
    fun record(name: String, result: OpResult) {
        when (result) {
            is OpResult.Ok -> applied += name
            is OpResult.Unsupported -> unsupported += "$name: ${result.reason}"
            is OpResult.Failed -> failed += "$name: ${result.reason}"
        }
    }

    fun note(name: String) {
        applied += name
    }

    fun markUnsupported(name: String, reason: String) {
        unsupported += "$name: $reason"
    }

    fun markFailed(name: String, reason: String) {
        failed += "$name: $reason"
    }

    fun merge(other: EnforceReport): EnforceReport {
        applied += other.applied
        unsupported += other.unsupported
        failed += other.failed
        return this
    }

    val hasFailure: Boolean get() = failed.isNotEmpty()
    val hasDegradation: Boolean get() = unsupported.isNotEmpty()

    /** 压缩成可放进事件 detail 的摘要（避免上报体积过大） */
    fun summary(): Map<String, String> = mapOf(
        "appliedCount" to applied.size.toString(),
        "unsupportedCount" to unsupported.size.toString(),
        "failedCount" to failed.size.toString(),
        "unsupported" to unsupported.take(10).joinToString("; ").take(900),
        "failed" to failed.take(10).joinToString("; ").take(900)
    )

    override fun toString(): String =
        "applied=${applied.size}, unsupported=${unsupported.size}, failed=${failed.size}"
}
