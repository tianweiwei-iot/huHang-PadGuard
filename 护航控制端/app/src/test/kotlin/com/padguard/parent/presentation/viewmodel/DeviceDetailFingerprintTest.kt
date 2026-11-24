package com.padguard.parent.presentation.viewmodel

import com.padguard.parent.domain.model.ControlMode
import com.padguard.parent.domain.model.Device
import com.padguard.parent.domain.model.DeviceOnlineStatus
import com.padguard.parent.domain.model.ProtectionLevel
import com.padguard.parent.domain.model.SceneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 设备详情页「实时字段指纹」的纯函数契约（R32）。
 *
 * 详情页原本只在 init 拉一次，孩子端改资料 / 守护进程掉线 / 家长补设操作密码后，
 * 家长停在详情页看到的全是快照，而首页 ≤10s 已是新值 —— 同 App 内自相矛盾。
 * 现在靠 10 秒设备列表轮询的指纹变化触发静默重拉，指纹字段集合就是这套机制的开关：
 * - 漏掉一个管控字段 → 该字段永远不刷新（回到本轮缺陷）；
 * - 多收一个高频字段（尤其 lastOnlineTime，每次心跳都变）→ 每 10 秒重拉一次详情，
 *   一次重拉带 4 个请求，白白放大服务端负载 24 倍。
 *
 * 因此这里把「该收 / 不该收」逐项钉死。
 */
class DeviceDetailFingerprintTest {

    private fun device() = Device(
        id = "dev-1",
        name = "小明的平板",
        deviceId = "hw-0001",
        model = "AGS6-W10",
        osVersion = "Android 10",
        appVersion = "1.0.4",
        onlineStatus = DeviceOnlineStatus.ONLINE,
        lastOnlineTime = 1_000L,
        batteryLevel = 50,
        controlMode = ControlMode.NORMAL,
        groupId = null,
        groupName = null,
        sceneType = SceneType.FAMILY,
        latitude = 30.0,
        longitude = 120.0
    )

    @Test
    fun `同一台设备指纹稳定 不因重复轮询产生变化`() {
        assertEquals(device().detailFingerprint(), device().detailFingerprint())
    }

    @Test
    fun `孩子端资料三字段任一变化都必须触发重拉`() {
        val base = device().detailFingerprint()
        assertNotEquals(base, device().copy(childName = "小明").detailFingerprint())
        assertNotEquals(base, device().copy(childNickname = "小小").detailFingerprint())
        assertNotEquals(base, device().copy(childAvatar = "https://x/a.png").detailFingerprint())
    }

    @Test
    fun `设备台账字段变化必须触发重拉`() {
        val base = device().detailFingerprint()
        assertNotEquals(base, device().copy(name = "学习平板").detailFingerprint())
        assertNotEquals(base, device().copy(model = "Pad 6").detailFingerprint())
        assertNotEquals(base, device().copy(appVersion = "1.0.5").detailFingerprint())
        assertNotEquals(base, device().copy(groupId = "g1").detailFingerprint())
        assertNotEquals(base, device().copy(sceneType = SceneType.SCHOOL).detailFingerprint())
    }

    @Test
    fun `管控态与守护健康度变化必须触发重拉`() {
        val base = device().detailFingerprint()
        assertNotEquals(base, device().copy(guardPasswordSet = true).detailFingerprint())
        assertNotEquals(base, device().copy(remoteLocked = true).detailFingerprint())
        assertNotEquals(base, device().copy(sceneMode = ControlMode.FOCUS).detailFingerprint())
        assertNotEquals(base, device().copy(protectionLevel = ProtectionLevel.HARD).detailFingerprint())
        assertNotEquals(base, device().copy(daemonAlive = false).detailFingerprint())
        assertNotEquals(base, device().copy(a11yAlive = false).detailFingerprint())
        assertNotEquals(base, device().copy(daemonLost = true).detailFingerprint())
        assertNotEquals(base, device().copy(permissionVersion = 7).detailFingerprint())
        assertNotEquals(base, device().copy(lastRestartReason = "force_stop").detailFingerprint())
        assertNotEquals(base, device().copy(batteryLevel = 51).detailFingerprint())
    }

    @Test
    fun `lastOnlineTime 每次心跳都变 绝不能进指纹`() {
        // 列表 10 秒一轮，这个字段每轮都不同；纳入会退化成每 10 秒 4 个请求
        assertEquals(
            device().copy(lastOnlineTime = 1_000L).detailFingerprint(),
            device().copy(lastOnlineTime = 9_999_999L).detailFingerprint()
        )
    }

    @Test
    fun `在线态与经纬度不进指纹 由各自的就地同步和独立接口负责`() {
        val base = device().detailFingerprint()
        assertEquals(base, device().copy(onlineStatus = DeviceOnlineStatus.OFFLINE).detailFingerprint())
        assertEquals(base, device().copy(latitude = 40.0, longitude = 116.0).detailFingerprint())
        assertEquals(base, device().copy(id = "dev-2", deviceId = "hw-0002").detailFingerprint())
    }

    @Test
    fun `分隔符是控制字符 昵称里的竖线不会造成指纹碰撞`() {
        // 若用 "|" 拼接，(childNickname="a|b", name="c") 与 (childNickname="a", name="b|c")
        // 可能撞成同一个指纹，导致资料改了却不刷新
        val a = device().copy(name = "c", childNickname = "a|b")
        val b = device().copy(name = "b|c", childNickname = "a")
        assertNotEquals(a.detailFingerprint(), b.detailFingerprint())
    }
}
