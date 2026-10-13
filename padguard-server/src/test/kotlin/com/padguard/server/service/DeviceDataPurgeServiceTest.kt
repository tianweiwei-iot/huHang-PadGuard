package com.padguard.server.service

import com.padguard.server.repository.AppDistributionRepository
import com.padguard.server.repository.AppPolicyRepository
import com.padguard.server.repository.AlertRepository
import com.padguard.server.repository.CommandRepository
import com.padguard.server.repository.DeviceEventRepository
import com.padguard.server.repository.DeviceFeaturePolicyRepository
import com.padguard.server.repository.DevicePermissionPolicyRepository
import com.padguard.server.repository.DeviceSettingRepository
import com.padguard.server.repository.GeofenceZoneRepository
import com.padguard.server.repository.InstalledAppRepository
import com.padguard.server.repository.LocationTrackRepository
import com.padguard.server.repository.MediaTaskRepository
import com.padguard.server.repository.PublishedMessageRepository
import com.padguard.server.repository.ScreenshotRepository
import com.padguard.server.repository.TimeRestrictionRepository
import com.padguard.server.repository.UnlockTicketRepository
import com.padguard.server.repository.UploadedFileRepository
import com.padguard.server.repository.UsageLogRepository
import com.padguard.server.repository.UsageSessionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * 设备私有数据清理（W19）覆盖度测试。
 *
 * 为什么值得单独立测试：W19 的病灶是"逐表 runCatching 吞错 + 漏清 3 张表"——
 * 在 PostgreSQL 上事务内吞错并不成立（一语句失败整事务仅回滚），
 * 于是"漏清"被静默掩盖成"成功"，解绑隐私删除承诺实际不可信。
 * 本测试盯住修复后的两条契约：
 * 1. **覆盖度**：purge 必须调用**全部 19 张设备私有表**的 deleteByDeviceId
 *    （新增私有表而忘记在这里补一行，本测试立刻红——这正是当年漏表的失败模式）；
 * 2. **异常上抛**：任一表删除失败必须 propagate，绝不吞成"成功"。
 *
 * 实现说明：purge 只与仓储的 deleteByDeviceId 交互（不读不写实体），
 * 因此用动态代理记录调用即可，无需为 19 个仓储各写一份内存 fake
 * （那样本文件会被 300 行样板代码淹没，真正要断言的契约反而看不清）。
 */
class DeviceDataPurgeServiceTest {

    /** 每个仓储 fake 记录到的 deleteByDeviceId 入参（未调用为 null）。 */
    private class RecordingRepos {
        val calls = LinkedHashMap<Class<*>, String>()
        /** 让指定接口的 deleteByDeviceId 抛错，用于验证异常上抛。 */
        var failOn: Class<*>? = null

        @Suppress("UNCHECKED_CAST")
        fun <T> fake(interfaceClass: Class<T>): T =
            Proxy.newProxyInstance(
                interfaceClass.classLoader,
                arrayOf(interfaceClass)
            ) { proxy, method, args ->
                when {
                    method.name == "deleteByDeviceId" -> {
                        if (interfaceClass == failOn) throw RuntimeException("模拟删除失败")
                        calls[interfaceClass] = args[0] as String
                        3
                    }
                    method.name == "toString" -> "Fake${interfaceClass.simpleName}"
                    method.name == "hashCode" -> System.identityHashCode(proxy)
                    method.name == "equals" -> proxy === args[0]
                    else -> error(" purge 不应调用 ${interfaceClass.simpleName}.${method.name}")
                }
            } as T
    }

    /** 19 张设备私有表 ↔ 仓储接口。purge 少任何一张，这条断言就会红。 */
    private fun RecordingRepos.service(): DeviceDataPurgeService = DeviceDataPurgeService(
        usageLogRepository = fake(UsageLogRepository::class.java),
        screenshotRepository = fake(ScreenshotRepository::class.java),
        installedAppRepository = fake(InstalledAppRepository::class.java),
        commandRepository = fake(CommandRepository::class.java),
        deviceEventRepository = fake(DeviceEventRepository::class.java),
        alertRepository = fake(AlertRepository::class.java),
        locationTrackRepository = fake(LocationTrackRepository::class.java),
        geofenceRepository = fake(GeofenceZoneRepository::class.java),
        publishedMessageRepository = fake(PublishedMessageRepository::class.java),
        mediaTaskRepository = fake(MediaTaskRepository::class.java),
        unlockTicketRepository = fake(UnlockTicketRepository::class.java),
        usageSessionRepository = fake(UsageSessionRepository::class.java),
        uploadedFileRepository = fake(UploadedFileRepository::class.java),
        deviceSettingRepository = fake(DeviceSettingRepository::class.java),
        appDistributionRepository = fake(AppDistributionRepository::class.java),
        appPolicyRepository = fake(AppPolicyRepository::class.java),
        timeRestrictionRepository = fake(TimeRestrictionRepository::class.java),
        devicePermissionPolicyRepository = fake(DevicePermissionPolicyRepository::class.java),
        deviceFeaturePolicyRepository = fake(DeviceFeaturePolicyRepository::class.java)
    )

    /** W19 修复时补齐的三张表——当年正是漏了它们，隐私删除承诺才不可信。 */
    private val historicallyMissed = listOf(
        DeviceEventRepository::class.java,
        UsageSessionRepository::class.java,
        UploadedFileRepository::class.java
    )

    @Test
    fun `purge 覆盖全部 19 张设备私有表`() {
        val repos = RecordingRepos()
        val removed = repos.service().purge("dev-42", "UNBIND")

        assertEquals(19, repos.calls.size, "purge 必须清完全部 19 张设备私有表，漏一张就是隐私残留")
        assertTrue(
            repos.calls.all { it.value == "dev-42" },
            "每张表都必须按传入的 deviceId 清理，实际调用：${repos.calls}"
        )
        // 当年漏清的三张表必须确认真清到了
        historicallyMissed.forEach { iface ->
            assertEquals("dev-42", repos.calls[iface], "漏清 ${iface.simpleName}：W19 修复表")
        }
        // 返回值必须含全部表 key 且行数来自各表 delete 的返回值
        assertEquals(19, removed.size)
        assertTrue(removed.values.all { it == 3 }, "各表删除行数应取仓储返回值，实际：$removed")
    }

    @Test
    fun `purge 任一表删除失败即上抛，绝不吞成假成功`() {
        val repos = RecordingRepos()
        repos.failOn = ScreenshotRepository::class.java // 清理顺序里第一张就是截图（最敏感）

        assertThrows(RuntimeException::class.java) {
            repos.service().purge("dev-42", "UNBIND")
        }
        // fail-fast：截图表（顺序首位）失败后不应继续碰其余表——
        // 让异常直接抛出，@Transactional 才能整轮回滚，由上层重试。
        assertEquals(0, repos.calls.size, "异常上抛后不应继续清理其余表，实际调用：${repos.calls.keys}")
    }
}
