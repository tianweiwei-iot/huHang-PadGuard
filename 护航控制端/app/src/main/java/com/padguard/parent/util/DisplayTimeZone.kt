package com.padguard.parent.util

import java.util.TimeZone

/**
 * App 端展示 / 日切统一使用的时区：北京时间（UTC+8）。
 *
 * 必须与服务端 `padguard-server/common/TimeZones.kt` 的 `SERVER_ZONE_ID` 取同一个值：
 * 服务端按它对使用时长做按天聚合与日切，界面若按**设备时区**显示同一个毫秒时间戳，
 * 设备时区一旦不是北京时间（家长/孩子自行改了系统时区、或境外使用），
 * 就会出现「服务端算到今天、界面显示成昨天」的错位，且这种差异只在同一份数据上漂移。
 *
 * Asia/Shanghai 自 1991 年起不再实行夏令时，全年恒定 +08:00，等价于 UTC+8。
 */
val DISPLAY_TIME_ZONE: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

/** 时区 ID 字面量，供需要字符串的 API（如 `SimpleDateFormat` 之外的 `java.time` 调用）使用。 */
const val DISPLAY_ZONE_ID: String = "Asia/Shanghai"
