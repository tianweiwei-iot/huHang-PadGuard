package com.padguard.data.model

import com.padguard.domain.model.AppUsage
import com.padguard.domain.model.UsageStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 服务端 `UsageStatsDto` / `AppUsageDto` 转家长端领域模型。
 *
 * 与 [com.padguard.parent.data.remote.ApiDataSource] 中原有的私有 `toDomain` 口径一致，
 * 抽成公共扩展供 WS 实时推送路径复用——推送报文与服务端 `UsageStatsDto` 字段完全一致，
 * 解析后直接得到领域模型，首页无需再发一次 HTTP 请求。
 */

fun UsageStatsDto.toDomain(): UsageStats = UsageStats(
    deviceId = deviceId,
    date = date,
    totalUsageMinutes = totalUsageMinutes,
    appUsages = appUsages.orEmpty().map { it.toDomain() }
)

fun AppUsageDto.toDomain(): AppUsage = AppUsage(
    packageName = packageName,
    appName = appName,
    usageMinutes = usageMinutes,
    iconUrl = iconUrl,
    // 起止时间：服务端按天聚合出的真实墙钟毫秒区间，转成界面可直接显示的 HH:mm:ss
    startTime = startAt?.takeIf { it > 0 }?.let { clockOf(it) },
    endTime = endAt?.takeIf { it > 0 }?.let { clockOf(it) },
    startAt = startAt,
    endAt = endAt,
    launchCount = launchCount
)

private fun clockOf(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))
