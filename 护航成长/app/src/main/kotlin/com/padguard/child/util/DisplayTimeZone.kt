package com.padguard.child.util

import java.util.TimeZone

/**
 * 孩子端展示层统一使用的时区：北京时间（UTC+8）。
 *
 * 必须与服务端 `padguard-server/common/TimeZones.kt` 的 `SERVER_ZONE_ID` 同值：
 * 服务端按 Asia/Shanghai 做使用时长聚合与日切，界面若按**设备时区**显示同一毫秒时间戳，
 * 设备时区被改到非北京时间时，就会出现「服务端算到今天、界面显示成昨天」。
 *
 * Asia/Shanghai 自 1991 年起不再实行夏令时，全年恒定 +08:00，等价于 UTC+8。
 */
val DISPLAY_TIME_ZONE: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")
