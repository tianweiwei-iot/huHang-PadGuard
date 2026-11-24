package com.padguard.server.service

import com.padguard.server.domain.AppDistribution
import com.padguard.server.repository.AppDistributionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * R-P2-12：分发台账状态收敛。
 *
 * 锁住三条语义，防止后续改动把它们悄悄改回去：
 * 1. 回执 SUCCESS **不等于** 安装完成（孩子端语义是"会话已受理"），只能是 DISPATCHED；
 * 2. 只有应用台账上报出现该包，才允许收敛到 SUCCESS；
 * 3. 终态不回退，且超窗口的陈旧分发不被后续手动安装"补成功"。
 */
class AppDistributionConvergerTest {

    private class FakeAppDistributionRepo :
        FakeJpaRepository<AppDistribution, String>(), AppDistributionRepository {
        override val table = LinkedHashMap<String, AppDistribution>()
        override fun idOf(entity: AppDistribution) = entity.id

        override fun findByUserIdAndDeviceId(userId: String, deviceId: String) =
            table.values.filter { it.userId == userId && it.deviceId == deviceId }

        override fun findByDeviceIdAndCatalogId(deviceId: String, catalogId: String) =
            table.values.filter { it.deviceId == deviceId && it.catalogId == catalogId }

        override fun findByMsgIdAndDeviceId(msgId: String, deviceId: String) =
            table.values.filter { it.msgId == msgId && it.deviceId == deviceId }

        override fun findByDeviceIdAndStatusIn(deviceId: String, statuses: Collection<String>) =
            table.values.filter { it.deviceId == deviceId && it.status in statuses }

        override fun deleteByDeviceId(deviceId: String): Int {
            val hit = table.values.filter { it.deviceId == deviceId }
            hit.forEach { table.remove(it.id) }
            return hit.size
        }

        override fun applyAckIfNotSuccess(
            id: String, status: String, errorCode: String?, errorMessage: String?,
            hasError: Int, finishedAt: Long?, success: String
        ): Int {
            val row = table[id] ?: return 0
            if (row.status == success) return 0
            row.status = status
            row.finishedAt = finishedAt
            if (hasError == 1) {
                row.errorCode = errorCode
                row.errorMessage = errorMessage
            }
            return 1
        }

        override fun markSuccessIfOpen(
            id: String, finishedAt: Long, success: String, openStatuses: Collection<String>
        ): Int {
            val row = table[id] ?: return 0
            if (row.status !in openStatuses) return 0
            row.status = success
            row.finishedAt = finishedAt
            row.errorCode = null
            row.errorMessage = null
            return 1
        }
    }

    private fun seed(
        repo: FakeAppDistributionRepo,
        id: String,
        deviceId: String = "dev-A",
        msgId: String = "m-$id",
        packageName: String = "com.example.app",
        status: String = AppDistributionConverger.STATUS_PENDING,
        createdAt: Long = System.currentTimeMillis()
    ): AppDistribution = AppDistribution(
        id = id, userId = "u1", deviceId = deviceId, catalogId = "c1",
        packageName = packageName, versionCode = 12, kind = "INSTALL",
        msgId = msgId, status = status, createdAt = createdAt
    ).also { repo.save(it) }

    @Test
    fun `ack SUCCESS only marks DISPATCHED not installed`() {
        val repo = FakeAppDistributionRepo()
        val record = seed(repo, "d1")
        val converger = AppDistributionConverger(repo)

        converger.onCommandAck("m-d1", "dev-A", "SUCCESS", null, "ACCEPTED")

        assertEquals(AppDistributionConverger.STATUS_DISPATCHED, record.status)
        assertNull(record.finishedAt, "已受理不是完成，不能写完成时间")
    }

    @Test
    fun `ack FAILED marks FAILED with error detail`() {
        val repo = FakeAppDistributionRepo()
        val record = seed(repo, "d1")
        val converger = AppDistributionConverger(repo)

        converger.onCommandAck("m-d1", "dev-A", "FAILED", "INSTALL_FAILED", "APK 校验失败")

        assertEquals(AppDistributionConverger.STATUS_FAILED, record.status)
        assertEquals("INSTALL_FAILED", record.errorCode)
        assertEquals("APK 校验失败", record.errorMessage)
    }

    @Test
    fun `UNSUPPORTED and REJECTED are failures, DUPLICATED is success`() {
        val repo = FakeAppDistributionRepo()
        val unsupported = seed(repo, "d1")
        val rejected = seed(repo, "d2")
        val duplicated = seed(repo, "d3")
        val converger = AppDistributionConverger(repo)

        converger.onCommandAck("m-d1", "dev-A", "UNSUPPORTED", "NO_DEVICE_OWNER", "需要 DO")
        converger.onCommandAck("m-d2", "dev-A", "REJECTED", "POLICY", "策略拒绝")
        converger.onCommandAck("m-d3", "dev-A", "DUPLICATED", null, null)

        assertEquals(AppDistributionConverger.STATUS_FAILED, unsupported.status)
        assertEquals(AppDistributionConverger.STATUS_FAILED, rejected.status)
        assertEquals(AppDistributionConverger.STATUS_SUCCESS, duplicated.status)
    }

    @Test
    fun `ack converges only the matching device`() {
        val repo = FakeAppDistributionRepo()
        val mine = seed(repo, "d1", deviceId = "dev-A")
        // 同一 msgId 但属于别的设备：绝不能被别的设备的回执改写
        val other = seed(repo, "d2", deviceId = "dev-B")
        val converger = AppDistributionConverger(repo)

        converger.onCommandAck("m-d1", "dev-A", "SUCCESS", null, null)

        assertEquals(AppDistributionConverger.STATUS_DISPATCHED, mine.status)
        assertEquals(AppDistributionConverger.STATUS_PENDING, other.status)
    }

    @Test
    fun `inventory report converges to SUCCESS`() {
        val repo = FakeAppDistributionRepo()
        val record = seed(repo, "d1", status = AppDistributionConverger.STATUS_DISPATCHED)
        val converger = AppDistributionConverger(repo)

        converger.onInventoryInstalled("dev-A", listOf("com.example.app", "com.other"))

        assertEquals(AppDistributionConverger.STATUS_SUCCESS, record.status)
        assertNotNull(record.finishedAt)
    }

    @Test
    fun `inventory report also rescues a FAILED record`() {
        // 设备先回 FAILED（如安装会话被中断），但重试后确实装上了：以台账为准
        val repo = FakeAppDistributionRepo()
        val record = seed(repo, "d1", status = AppDistributionConverger.STATUS_FAILED)
        val converger = AppDistributionConverger(repo)

        converger.onInventoryInstalled("dev-A", listOf("com.example.app"))

        assertEquals(AppDistributionConverger.STATUS_SUCCESS, record.status)
        assertNull(record.errorCode, "收敛成功后应清掉旧错误码")
    }

    @Test
    fun `stale distribution is not rescued by a later manual install`() {
        val repo = FakeAppDistributionRepo()
        val record = seed(
            repo, "d1", status = AppDistributionConverger.STATUS_PENDING,
            createdAt = System.currentTimeMillis() - AppDistributionConverger.CONVERGE_WINDOW_MS - 1
        )
        val converger = AppDistributionConverger(repo)

        converger.onInventoryInstalled("dev-A", listOf("com.example.app"))

        assertEquals(AppDistributionConverger.STATUS_PENDING, record.status, "超窗口的陈旧分发不该被补成功")
    }

    @Test
    fun `SUCCESS is terminal and cannot regress`() {
        val repo = FakeAppDistributionRepo()
        val record = seed(repo, "d1", status = AppDistributionConverger.STATUS_SUCCESS)
        val converger = AppDistributionConverger(repo)

        converger.onCommandAck("m-d1", "dev-A", "FAILED", "X", "迟到的失败回执")

        assertEquals(AppDistributionConverger.STATUS_SUCCESS, record.status)
        assertNull(record.errorCode)
    }

    @Test
    fun `blank msgId or unknown package is a no-op`() {
        val repo = FakeAppDistributionRepo()
        val record = seed(repo, "d1")
        val converger = AppDistributionConverger(repo)

        converger.onCommandAck("", "dev-A", "SUCCESS", null, null)
        converger.onInventoryInstalled("dev-A", listOf("com.someone.else"))

        assertEquals(AppDistributionConverger.STATUS_PENDING, record.status)
    }
}
