package com.padguard.server.service

import com.padguard.server.domain.AppDistribution
import com.padguard.server.repository.AppDistributionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 第 7 批红护栏：应用分发台账「单向、终态不可回退」在**并发**下并不成立。
 *
 * `AppDistributionConverger` 的类注释明确声明状态机单向，代码里也写了
 * `if (record.status == STATUS_SUCCESS) continue`，但那只是**内存读判**：
 * `findByMsgIdAndDeviceId` 拿到的是脱管实体，随后 `save(record)` 是整行 merge。
 * 现存 `AppDistributionConvergerTest.SUCCESS is terminal and cannot regress` 是单线程
 * 顺序调用，读到的就是最新值，测不出这个窗口。
 *
 * 这里让仓储返回**独立快照**，并在返回后模拟另一路「应用台账上报」把权威行推到
 * SUCCESS —— 还原一条 MQTT QoS1 迟到的 FAILED 回执把「已装好」打回「安装失败」的场景。
 */
@DisplayName("应用分发台账 —— SUCCESS 终态在并发下不可回退")
class AppDistributionTerminalConcurrencyTest {

    private class SnapshotRepo : FakeJpaRepository<AppDistribution, String>(), AppDistributionRepository {
        override val table = LinkedHashMap<String, AppDistribution>()
        override fun idOf(entity: AppDistribution) = entity.id

        /** 置为 true 时，下一次 findByMsgIdAndDeviceId 之后模拟并发的 inventory 上报。 */
        var raceHook = false

        override fun findByUserIdAndDeviceId(userId: String, deviceId: String) =
            table.values.filter { it.userId == userId && it.deviceId == deviceId }

        override fun findByDeviceIdAndCatalogId(deviceId: String, catalogId: String) =
            table.values.filter { it.deviceId == deviceId && it.catalogId == catalogId }

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

        override fun findByMsgIdAndDeviceId(msgId: String, deviceId: String): List<AppDistribution> {
            val hits = table.values.filter { it.msgId == msgId && it.deviceId == deviceId }
            // 顺序要命：必须先复制出旧快照，再模拟并发提交。若反过来，hits 与 table 持有
            // 同一对象引用，快照会跟着变成 SUCCESS，`if (status == SUCCESS) continue` 直接跳过，
            // 缺陷被掩盖、护栏永远绿（第 2 批记过同型坑）。
            val snapshots = hits.map { snapshotOf(it) }
            if (raceHook) {
                raceHook = false
                // 另一路（应用台账上报）已在本次查询之后提交，权威行进入终态。
                hits.forEach { table[it.id]!!.status = AppDistributionConverger.STATUS_SUCCESS }
            }
            return snapshots
        }

        private fun snapshotOf(e: AppDistribution) = AppDistribution(
            id = e.id, userId = e.userId, deviceId = e.deviceId, catalogId = e.catalogId,
            packageName = e.packageName, versionCode = e.versionCode, kind = e.kind, msgId = e.msgId,
            status = e.status, errorCode = e.errorCode, errorMessage = e.errorMessage,
            createdAt = e.createdAt, finishedAt = e.finishedAt
        )
    }

    private fun seed(repo: SnapshotRepo, status: String = AppDistributionConverger.STATUS_PENDING) {
        repo.save(
            AppDistribution(
                id = "d1", userId = "u1", deviceId = "dev-A", packageName = "com.example.app",
                msgId = "m1", status = status, createdAt = System.currentTimeMillis()
            )
        )
    }

    @Test
    @DisplayName("SUCCESS 之后迟到的 FAILED 回执不得把终态打回，也不得写入错误码")
    fun `late FAILED ack cannot regress a concurrently committed SUCCESS`() {
        val repo = SnapshotRepo()
        seed(repo)
        repo.raceHook = true

        AppDistributionConverger(repo).onCommandAck("m1", "dev-A", "FAILED", "LATE_ACK", "迟到的失败回执")

        val row = repo.table["d1"]!!
        assertEquals(
            AppDistributionConverger.STATUS_SUCCESS, row.status,
            "旧快照整行 merge 把已完成的安装覆盖回 FAILED；家长端『已装好』变『安装失败』"
        )
        assertNull(row.errorCode, "终态还被写入了迟到回执的错误码")
    }

    @Test
    @DisplayName("正常回执仍能把 PENDING 推进到 FAILED（修法不能把功能改没）")
    fun `a normal failed ack still advances a pending record`() {
        val repo = SnapshotRepo()
        seed(repo)

        AppDistributionConverger(repo).onCommandAck("m1", "dev-A", "FAILED", "INSTALL_FAILED", "APK 校验失败")

        val row = repo.table["d1"]!!
        assertEquals(AppDistributionConverger.STATUS_FAILED, row.status)
        assertEquals("INSTALL_FAILED", row.errorCode)
    }
}
