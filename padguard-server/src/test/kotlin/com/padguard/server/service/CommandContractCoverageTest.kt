package com.padguard.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * 下行指令契约守门测试（跨端口径漂移防线）。
 *
 * ## 为什么需要它
 * 指令类型与 payload 字段名是服务端与孩子端之间的**隐式字符串约定**，两端口径分处
 * 两个独立 Gradle 工程，编译器无法互相约束。一旦漂移，后果是：
 *
 * - 指令类型漂移 → 孩子端按未知指令丢弃，服务端对家长端仍返回 200；
 * - 字段名漂移（**已实际发生**：`SHOW_MESSAGE` 曾服务端下发 `text`、孩子端读 `content`，
 *   信息发布功能长期不可用且无任何服务端错误）→ 孩子端以 `BAD_PAYLOAD` 拒绝执行。
 *
 * 两种形态外部表现一致：「操作成功、设备毫无反应」，极难排查。
 *
 * ## 这份清单怎么维护
 * 与 [AlertTitleCoverageTest] 同一模式：**刻意手工抄写**孩子端的口径作为 ground truth
 * （自动跨工程抓取在 CI 上不可靠）。代价是孩子端改口径要同步改这里；
 * 收益是漂移在 `./gradlew test` 当场失败，而不是变成一条静默失效的指令。
 *
 * 同步来源：
 * - 指令类型：`护航成长/core/data/src/main/kotlin/com/padguard/core/data/model/Command.kt`（`enum class CommandType`）
 * - payload 键：`护航成长/core/engine/src/main/kotlin/com/padguard/core/engine/command/CommandExecutor.kt`（`KEY_*` 常量）
 * - feature 键：`护航成长/core/engine/src/main/kotlin/com/padguard/core/engine/admin/DeviceAdminBridge.kt`（`FeatureKey`）
 *
 * 若本测试失败，正确的修法是**核对孩子端源码后改契约或清单**，
 * 而不是单方面改孩子端——两端不一致本身就是事故。
 */
class CommandContractCoverageTest {

    // ==================== ground truth：孩子端口径（手工抄写） ====================

    /** 孩子端 `enum class CommandType` 全部取值。 */
    private val childCommandTypes = setOf(
        "FLUSH_LOGS", "INSTALL_APP", "LIVE_VIEW_START", "LIVE_VIEW_STOP", "LOCATE",
        "LOCK_SCREEN", "PERIPHERAL_OVERRIDE", "PHOTO", "REBOOT", "RECORD",
        "REFRESH_POLICY", "RESET_POLICY", "REVEAL_MANAGEMENT_UI", "SCREENSHOT", "SCREEN_RECORD",
        "SET_APP_HIDDEN", "SET_APP_SUSPENDED", "SET_FEATURE_POLICY", "SET_MANAGEMENT_ACTIVE",
        "SET_RUNTIME_PERMISSION", "SET_WATERMARK", "SHOW_MESSAGE", "SHUTDOWN", "START_TIMER_CONFIRM",
        "STOP_RECORD", "STOP_SCREEN_RECORD", "TEMP_UNLOCK", "UNINSTALL_APP", "UNLOCK", "UPDATE_CONFIG", "WIPE"
    )

    /**
     * 孩子端已实现、但服务端**从未下发**的休眠能力（重启/关机/恢复出厂等高危动作
     * 与外设临时放行，暂无家长端入口）。契约不登记它们不算漂移；但任何一条要启用时，
     * 必须先在 [CommandContract.kt] 的 CommandType 里登记，并把名字从这里挪出去。
     */
    private val dormantCommands = setOf(
        "PERIPHERAL_OVERRIDE", "REBOOT", "RESET_POLICY", "SHUTDOWN", "WIPE"
    )

    /**
     * 孩子端 `CommandExecutor` 的 `KEY_*` 常量（指令 payload 字段名 → 字符串值）。
     * ITEM / CONFIRM 属于休眠指令（外设放行 / WIPE 二次确认），服务端不下发。
     */
    private val childPayloadKeys = mapOf(
        "ACTIVE" to "active",
        "APK_URL" to "apkUrl",
        "BLOCKING" to "blocking",
        "CONFIRM" to "confirm",
        "CONTENT" to "content",
        "CONTENT_TYPE" to "contentType",
        "DURATION_MINUTES" to "durationMinutes",
        "DURATION_SEC" to "durationSec",
        "ENABLED" to "enabled",
        "GRANT" to "grant",
        "HIGH_DEFINITION" to "highDefinition",
        "HIDDEN" to "hidden",
        "ITEM" to "item",
        "MEDIA_NAME" to "mediaName",
        "MEDIA_URL" to "mediaUrl",
        "PACKAGE_NAME" to "packageName",
        "PERMISSION" to "permission",
        "REASON" to "reason",
        "REQUEST_ID" to "requestId",
        "RESOLUTION" to "resolution",
        "SHOT_ID" to "shotId",
        "SUSPENDED" to "suspended",
        "TASK_ID" to "taskId",
        "TITLE" to "title",
        "VERSION_CODE" to "versionCode",
        "WITH_AUDIO" to "withAudio"
    )

    /** 孩子端 `DeviceAdminBridge.FeatureKey`（SET_FEATURE_POLICY 的 featureKey → 字符串值）。 */
    private val childFeatureKeys = mapOf(
        "CAMERA_DISABLED" to "camera_disabled",
        "SCREENSHOT_DISABLED" to "screenshot_disabled",
        "FACTORY_RESET_DISABLED" to "factory_reset_disabled",
        "SAFE_BOOT_DISABLED" to "safe_boot_disabled",
        "UNKNOWN_SOURCES_DISABLED" to "unknown_sources_disabled",
        "CONFIG_DATETIME_DISABLED" to "config_datetime_disabled",
        "USB_DEBUG_DISABLED" to "usb_debug_disabled",
        "USB_DATA_DISABLED" to "usb_data_disabled",
        "STATUS_BAR_DISABLED" to "status_bar_disabled",
        "AUTO_TIME_ENFORCED" to "auto_time_enforced"
    )

    /**
     * 孩子端会读、但属于**上行**（媒体上传 multipart 字段）而非下行指令 payload 的键。
     * 单独列出防止误判；下行/上行语义变化时须重新归类。
     */
    private val uplinkOnlyKeys = setOf("TRIGGER_TYPE")

    /**
     * 孩子端 `LogType` 中属于**合规凭证**的类型（同步来源：`Event.kt`）。
     * 只列合规类而非全部 14 项：其余行为日志都是纯留档，不参与任何服务端动作判定。
     */
    private val childComplianceLogTypes = setOf("AGREEMENT")

    // ==================== 断言 ====================

    /** 用反射取 object 里全部 public static 常量字段（`const val` 编译产物）。 */
    private fun constantsOf(obj: Any): Map<String, String> =
        obj.javaClass.fields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .associate { it.name to it.get(null) as String }

    @Test
    fun `every contract command type exists in the child enum`() {
        val serverTypes = constantsOf(CommandType)
        val unknown = serverTypes.values.filter { it !in childCommandTypes }
        assertTrue(
            unknown.isEmpty(),
            "服务端契约登记了孩子端不认识的指令类型（孩子端会按未知指令丢弃，家长端却看到受理成功）：" +
                unknown.joinToString("、") + "。请核对 Command.kt 后修正 CommandType。"
        )
        // 防御：字段值与字段名不一致（契约约定两者相同），会让排查与反射双口径失效
        serverTypes.forEach { (name, value) ->
            assertEquals(name, value, "CommandType.$name 的值应为 \"$name\"（契约约定值与常量名一致）")
        }
    }

    @Test
    fun `every non-dormant child command is registered in the contract`() {
        val serverTypes = constantsOf(CommandType).keys
        val expected = childCommandTypes - dormantCommands
        val missing = expected - serverTypes
        assertTrue(
            missing.isEmpty(),
            "孩子端实现了、服务端却没有登记的指令（调用点只能裸写字符串，恰是 SHOW_MESSAGE 事故的形态）：" +
                missing.joinToString("、") + "。请在 CommandContract.CommandType 登记。"
        )
    }

    @Test
    fun `every contract payload key matches the child executor exactly`() {
        val serverKeys = constantsOf(CommandKey)
        serverKeys.forEach { (name, value) ->
            val childValue = when {
                name.startsWith("FEATURE_") -> childFeatureKeys[name.removePrefix("FEATURE_")]
                name in uplinkOnlyKeys -> return@forEach // 上行字段，不在下行 payload 比对范围
                else -> childPayloadKeys[name]
            }
            assertEquals(
                childValue,
                value,
                "CommandKey.$name 与孩子端口径不一致（孩子端读取 $childValue，服务端下发 $value）——" +
                    "这正是 SHOW_MESSAGE text/content 事故的形态，孩子端将以 BAD_PAYLOAD 静默拒绝。"
            )
        }
    }

    @Test
    fun `every non-dormant child payload key is registered in the contract`() {
        val serverKeys = constantsOf(CommandKey).keys
        val dormantKeys = childPayloadKeys.entries
            .filter { it.key == "ITEM" || it.key == "CONFIRM" }
            .map { it.key }
            .toSet()
        val missing = (childPayloadKeys.keys - dormantKeys) - serverKeys
        assertTrue(
            missing.isEmpty(),
            "孩子端会读取、服务端契约未登记的 payload 键：" + missing.joinToString("、") +
                "。下发点只能裸写魔法字符串，漂移风险回到契约之外。请在 CommandContract.CommandKey 登记。"
        )
    }

    @Test
    fun `feature key universe is closed on both ends`() {
        val serverFeatures = constantsOf(CommandKey)
            .filterKeys { it.startsWith("FEATURE_") }
            .mapKeys { it.key.removePrefix("FEATURE_") }
        assertEquals(
            childFeatureKeys,
            serverFeatures,
            "SET_FEATURE_POLICY 的 featureKey 两端口径不一致：孩子端 DeviceAdminBridge 只认自己清单里的键，" +
                "多出的键会落入\"无可识别的功能开关\"，缺失的键则永远无法下发。"
        )
    }

    @Test
    fun `compliance retention and query agree with the child log type`() {
        // 三处各自写着 "AGREEMENT"：清理器的保护集合、查询服务的过滤类型、孩子端枚举名。
        // 任一处改名都会让凭证被 TTL 删掉或永远查不到，而这个失败没有任何运行期报错。
        assertTrue(
            AgreementService.TYPE in childComplianceLogTypes,
            "AgreementService.TYPE 不是孩子端会上传的 LogType，查询会永远返回「未查到」"
        )
        assertEquals(
            childComplianceLogTypes,
            UsageLogCleanup.PROTECTED_TYPES,
            "合规凭证保护集合与孩子端口径不一致：漏登记的类型会被 30 天 TTL 无声删除，" +
                "多登记的则是一句永远匹配不到的死配置"
        )
    }
}
