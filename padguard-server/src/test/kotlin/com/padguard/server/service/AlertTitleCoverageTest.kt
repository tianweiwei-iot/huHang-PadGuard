package com.padguard.server.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `RiskType` 覆盖面守门测试（跨端口径漂移防线）。
 *
 * ## 为什么需要它
 * 孩子端 `RiskType` 枚举（`护航成长/core/data/.../model/Event.kt`）与服务端
 * [AlertService.EVENT_TITLES] 分处两个独立 Gradle 工程，**编译器无法互相约束**。
 * 孩子端新增一种风险类型后，只要服务端漏登记，家长端看到的就是：
 *
 * ```
 * 标题：LOCK_SCREEN_UNPROTECTED
 * 正文：设备事件
 * ```
 *
 * 这条告警本身往往正是最高危的那几类（锁屏失效、指令签名失败）。它不会报错、不会告警、
 * 不会被任何现有测试发现 —— 只有家长真正需要它的时候才会暴露，而那时已经晚了。
 *
 * ## 这份清单怎么维护
 * 它**刻意是手工抄写**的：自动化地从孩子端源码抓枚举值需要跨工程路径依赖，
 * 在 CI 上不可靠。代价是新增风险类型时要改两处（孩子端枚举 + 这里）；
 * 收益是漂移会在 `./gradlew test` 里当场失败，而不是在家长端变成一个看不懂的标题。
 *
 * 若本测试失败，正确的修法是：
 * 1. 在 [AlertService.EVENT_TITLES] 里补上中文标题与正文（**不要**改这个清单来迁就实现）；
 * 2. 确认家长端消息中心能把该 `category` 渲染成合理的分组/图标。
 */
class AlertTitleCoverageTest {

    /**
     * 孩子端 `enum class RiskType` 的全部取值（按字母序，便于与源码逐个核对）。
     *
     * 同步来源：`护航成长/core/data/src/main/kotlin/com/padguard/core/data/model/Event.kt`。
     */
    private val childRiskTypes = listOf(
        "ACCESSIBILITY_DISABLED",
        "APP_INSTALLED",
        "APP_UNINSTALLED",
        "BLACKLIST_APP_LAUNCH",
        "BLOCKED_URL_ACCESS",
        "CLEAR_DATA_ATTEMPT",
        "CLOCK_TAMPERING",
        "CONTROL_LOST",
        "DEVELOPER_OPTIONS_ENABLED",
        "DEBUG_BUILD_ACTIVE",
        "FORCE_STOP_ATTEMPT",
        "GUARD_PASSWORD_NOT_SET",
        "GUARD_PERMISSION_DROPPED",
        "GUARD_FOREGROUND_DOWN",
        "GUARD_PROCESS_RECYCLED",
        "HEARTBEAT_TIMEOUT",
        "HOME_CHANGED",
        "INVALID_SIGNATURE",
        "LOCK_SCREEN_UNPROTECTED",
        "PERMISSION_REVOKED",
        "POLICY_APPLY_FAILED",
        "POLICY_DEGRADED",
        "PROXY_DETECTED",
        "ROOT_DETECTED",
        "TIME_LIMIT_EXCEEDED",
        "UNINSTALL_ATTEMPT",
        "USAGE_ACCESS_LOST",
        "USB_DEBUG_ENABLED",
        "WEB_GUARD_DOWN"
    )

    @Test
    fun `every child risk type has a Chinese title and message`() {
        val missing = childRiskTypes.filter { it !in AlertService.EVENT_TITLES }
        assertTrue(
            missing.isEmpty(),
            "孩子端会上报但这些风险类型在服务端没有中文文案，家长端会看到英文枚举名：" +
                missing.joinToString("、") + "。请在 AlertService.EVENT_TITLES 中补齐。"
        )
    }

    @Test
    fun `no title or message is blank or equals the fallback text`() {
        childRiskTypes.forEach { type ->
            val (title, message) = AlertService.EVENT_TITLES.getValue(type)
            // 空标题会让消息中心出现一条"没有标题"的空白卡片
            assertFalse(title.isBlank(), "$type 的标题为空")
            assertFalse(message.isBlank(), "$type 的正文为空")
            // 兜底文案（"设备事件"）出现在具体类型上，说明只是占位、没有真正写文案
            assertFalse(title == "设备事件", "$type 仍在用兜底标题")
            // 标题不该是英文枚举名本身：那正是本次要消灭的形态
            assertFalse(title == type, "$type 的标题仍是英文枚举名")
        }
    }

    // 未知类型的中文兜底此前在此处用"断言测试内局部常量"验证（纯假绿：实现怎么改都通过）。
    // 已采纳行为断言：AlertServiceTest.`unmapped risk type falls back to readable Chinese title and message`
    // 直接调用 createFromEvent 并断言落库结果，能真正拦住"兜底退回英文枚举名"的回归。
}
