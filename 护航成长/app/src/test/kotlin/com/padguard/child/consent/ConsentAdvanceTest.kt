package com.padguard.child.consent

import com.padguard.child.permission.SpecialPermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 授权自动接续的「下一项」决策单测（纯 JVM，不依赖 Android 运行时）。
 *
 * 直接锁住用户核心诉求「一个授权完成后自动进入下一个、无需手动切换」的顺序逻辑：
 * [nextManualItemAfter] 必须顺着 [SpecialPermission] 声明顺序逐项推进，
 * 不能每次都从第一项重来（否则家长会看到"开完第 4 项被拽回第 1 项"的乱跳），
 * 也不能在游标落到末项后空转或越界。
 */
class ConsentAdvanceTest {

    /** 模拟 [OneTimeConsentViewModel.advanceInternal] 步骤④ 拿到的 manual 列表：按 SpecialPermission 声明顺序（= ordinal 顺序）。 */
    private val all = listOf(
        SpecialPermission.OVERLAY,
        SpecialPermission.USAGE_STATS,
        SpecialPermission.INSTALL_PACKAGES,
        SpecialPermission.ACCESSIBILITY,
        SpecialPermission.BATTERY_OPTIMIZATION,
        SpecialPermission.EXACT_ALARM,
        SpecialPermission.APP_NOTIFICATIONS,
        SpecialPermission.OEM_BACKGROUND,
    )

    @Test
    fun `首轮游标为 null 取第一项（悬浮窗）`() {
        assertEquals(SpecialPermission.OVERLAY, nextManualItemAfter(all, null))
    }

    @Test
    fun `刚办完悬浮窗 自动接续到使用情况访问`() {
        // 「允许在其它应用上层显示」开完，下一个应当是使用情况访问，而不是回到第一项
        assertEquals(SpecialPermission.USAGE_STATS, nextManualItemAfter(all, "overlay"))
    }

    @Test
    fun `游标在中间 取其后第一项而非回到首项`() {
        // 办完 accessibility（第4项）之后应接续到 batteryOptimization（第5项），不是 overlay
        assertEquals(
            SpecialPermission.BATTERY_OPTIMIZATION,
            nextManualItemAfter(all, "accessibility")
        )
    }

    @Test
    fun `游标是已办妥而不在 manual 中的项 仍顺着 ordinal 顺延`() {
        // manual 已剔除 usageStats（已办妥）。传入 usageStats 作游标，应顺延到其后的 installPackages
        val manual = all.filter { it != SpecialPermission.USAGE_STATS }
        assertEquals(SpecialPermission.INSTALL_PACKAGES, nextManualItemAfter(manual, "usageStats"))
    }

    @Test
    fun `游标是最后一项 回退到首项 不空转不越界`() {
        // oemBackground 是末项；办完后没有"其后"项，回退到 manual 首项而非抛异常 / 空指针
        val manual = all.dropLast(1) // 不含 oemBackground
        assertEquals(SpecialPermission.OVERLAY, nextManualItemAfter(manual, "oemBackground"))
    }

    @Test
    fun `列表只剩一项 始终返回那一项`() {
        val single = listOf(SpecialPermission.OVERLAY)
        assertEquals(SpecialPermission.OVERLAY, nextManualItemAfter(single, null))
        assertEquals(SpecialPermission.OVERLAY, nextManualItemAfter(single, "overlay"))
    }

    @Test
    fun `空列表返回 null 不崩溃`() {
        assertNull(nextManualItemAfter(emptyList(), "overlay"))
    }

    @Test
    fun `游标为未知 key 退化到首项 不空转`() {
        assertEquals(SpecialPermission.OVERLAY, nextManualItemAfter(all, "not-a-real-key"))
    }

    @Test
    fun `连续自动接续整轮 顺序不被打乱`() {
        // 模拟"悬浮窗→使用情况访问→…→厂商后台运行"逐项办妥后，每次都取到正确的下一项
        val visited = mutableListOf<String>()
        var cursor: String? = null
        var remaining = all
        while (remaining.isNotEmpty()) {
            val next = nextManualItemAfter(remaining, cursor)!!
            visited += next.key
            cursor = next.key
            remaining = remaining.filter { it.key != next.key }
        }
        assertEquals(
            "自动接续必须严格按清单顺序遍历全部项",
            all.map { it.key },
            visited
        )
    }
}
