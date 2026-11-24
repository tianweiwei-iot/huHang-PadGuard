package com.padguard.server.common

/**
 * 日志脱敏（阶段 1 体检 F-07）。
 *
 * 手机号属于个人信息，认证 / 绑定链路的 warn、info 日志此前直接写完整号码：
 * 有日志读取权的任何一方（运维、日志采集、排障时把日志贴进工单）都等于拿到用户手机号。
 * 这里只做一件事：把待写入日志的手机号变成不可还原的掩码。
 *
 * 非 11 位纯数字输入（空、超长、含字母、含换行等）一律返回固定占位，
 * 既不泄露原始值，也避免异常输入把控制字符原样写进日志。
 *
 * 注意：本函数**只用于日志**。业务判断、数据库写入、频控的账号维度键仍必须使用原始号码，
 * 否则会把「脱敏后的字符串」当成账号，造成频控桶错乱与查库失败。
 */
object SensitiveData {
    private const val PLACEHOLDER = "***"

    fun maskPhone(phone: String?): String {
        val value = phone?.trim() ?: return PLACEHOLDER
        if (value.length != 11 || !value.all { it in '0'..'9' }) return PLACEHOLDER
        return value.take(3) + "****" + value.takeLast(4)
    }
}
