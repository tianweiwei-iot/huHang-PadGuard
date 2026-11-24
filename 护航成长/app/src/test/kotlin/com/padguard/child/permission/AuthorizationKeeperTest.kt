package com.padguard.child.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isDeviceAdminMissing] 的纯 JVM 单测（R-P3-13）。
 *
 * 锁定「设备管理员缺失」的四条判定分支，重点是被旧实现吞掉的那条：
 * `daPromptAck` 一旦置位就永久豁免 —— 之后孩子真去设置里停用管理员，
 * 判定层仍然认为"家长自己不要的"，既不告警也不把家长拉回，
 * 设备从此长期无闸门（非 DO 设备尤其致命：管理员是唯一让"卸载需要两步"的机制）。
 */
class AuthorizationKeeperTest {

    private fun missing(
        active: Boolean = false,
        everActive: Boolean = false,
        promptAck: Boolean = false,
        hasOwner: Boolean = false
    ) = isDeviceAdminMissing(
        active = active,
        everActive = everActive,
        promptAck = promptAck,
        hasOwnerPrivileges = hasOwner
    )

    @Test
    fun `当前处于激活态时永远不算缺失`() {
        assertFalse(missing(active = true))
        // 即使同时存在各种标记，激活就是激活
        assertFalse(missing(active = true, everActive = true, promptAck = true, hasOwner = true))
    }

    @Test
    fun `DO 或 PO 形态下不参与缺失判定，跳过 DO 的标记不得串味成管理员缺失`() {
        // 家长在 DO 预置页选择"已知风险，继续"后，设备以非 DO 软管控运行，
        // isAdminActive() 天然为 false；旧实现据此反复拉家长回授权流程。
        assertFalse(missing(active = false, everActive = false, promptAck = false, hasOwner = true))
        assertFalse(missing(active = false, everActive = true, promptAck = false, hasOwner = true))
    }

    @Test
    fun `曾有过又丢了必须重开，家长的跳过标记不得豁免`() {
        // R-P3-13 的核心回归：这是唯一能反映"管理员被孩子停用"的信号，绝不能静默吞掉。
        assertTrue(missing(active = false, everActive = true, promptAck = true))
        assertTrue(missing(active = false, everActive = true, promptAck = false))
    }

    @Test
    fun `从未有过且家长已显式跳过时不提示，避免激活页点不了激活的机型死循环`() {
        assertFalse(missing(active = false, everActive = false, promptAck = true))
    }

    @Test
    fun `从未有过且未跳过时按缺失处理，走首次引导`() {
        assertTrue(missing(active = false, everActive = false, promptAck = false))
    }

    // ==================== 运行时权限：与特殊权限同用「曾授予又丢」纪律 ====================

    private val loc = "android.permission.ACCESS_FINE_LOCATION"
    private val cam = "android.permission.CAMERA"

    @Test
    fun `家长装机时主动拒绝的运行时权限不算脱管，杜绝反复拉回授权页`() {
        // 非 DO 设备没有静默补授，这项会一直缺失；若按「当前缺就算脱管」判定，
        // 每轮周期自检都会置 reauthRequired，每次打开 App 都强制进授权页且永远清不掉。
        assertTrue(
            "缺失清单非空，但从未授予过 → 不应产生任何待拉回项",
            droppedRuntimePermissions(listOf(loc), emptySet()).isEmpty()
        )
    }

    @Test
    fun `曾授予又掉的运行时权限必须算脱管`() {
        val dropped = droppedRuntimePermissions(listOf(loc, cam), setOf(loc))
        assertEquals(listOf(loc), dropped)
    }

    @Test
    fun `已恢复授予的权限不算脱管，且账本历史不被抹掉`() {
        // everGranted 是单向账本：即使当前不缺，历史仍在；纯函数按「当前 missing」过滤。
        assertTrue(droppedRuntimePermissions(emptyList(), setOf(loc, cam)).isEmpty())
        assertEquals(listOf(cam), droppedRuntimePermissions(listOf(cam), setOf(loc, cam)))
    }

    @Test
    fun `账本为空的首次自检不误报，且缺失清单顺序稳定`() {
        assertTrue(droppedRuntimePermissions(listOf(loc, cam), emptySet()).isEmpty())
        assertEquals(
            listOf(cam, loc),
            droppedRuntimePermissions(listOf(cam, loc), setOf(loc, cam))
        )
    }
}
