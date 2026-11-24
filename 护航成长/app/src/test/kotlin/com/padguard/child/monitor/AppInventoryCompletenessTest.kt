package com.padguard.child.monitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [inventoryIsIncomplete] 的纯 JVM 单测（R27）。
 *
 * 背景：台账采集的"部分失败"曾被逐项静默吞掉，返回一份**残缺但非空**的清单，
 * 被当作全量送去差分与上传。服务端按「本次没出现 = 已卸载」把没卸的应用标成已卸载，
 * 家长端一次收到几十条假卸载告警；基线被污染后下个周期再炸一批假新装告警。
 *
 * 判据必须同时守住两头：
 * 1. 系统性采集失败（意图解析抛异常 / 大面积取不到详情）→ 整轮作废；
 * 2. 孩子真的一次卸载很多应用 → 绝不能误判为采集失败。
 *    否则基线不推进、同样的收缩每轮都被判失败 → 装卸事件永久漏报（死锁）。
 */
class AppInventoryCompletenessTest {

    @Test
    fun `任一启动器意图解析抛异常 本轮一律作废`() {
        assertTrue(inventoryIsIncomplete(queryFailed = true, launcherCandidates = 0, infoFailures = 0))
        assertTrue(inventoryIsIncomplete(queryFailed = true, launcherCandidates = 40, infoFailures = 0))
    }

    @Test
    fun `正常采集不作废`() {
        assertFalse(inventoryIsIncomplete(queryFailed = false, launcherCandidates = 40, infoFailures = 0))
        assertFalse(inventoryIsIncomplete(queryFailed = false, launcherCandidates = 0, infoFailures = 0))
    }

    @Test
    fun `小样本不按比例判 避免装机初期台账迟迟建不起来`() {
        assertFalse(inventoryIsIncomplete(queryFailed = false, launcherCandidates = 3, infoFailures = 3))
        assertFalse(inventoryIsIncomplete(queryFailed = false, launcherCandidates = 1, infoFailures = 1))
    }

    @Test
    fun `大面积取不到PackageInfo 判为PMS整体异常`() {
        assertTrue(inventoryIsIncomplete(queryFailed = false, launcherCandidates = 10, infoFailures = 6))
        assertTrue(inventoryIsIncomplete(queryFailed = false, launcherCandidates = 40, infoFailures = 40))
    }

    @Test
    fun `半数取不到详情仍视为可用 阈值不能过敏`() {
        assertFalse(inventoryIsIncomplete(queryFailed = false, launcherCandidates = 10, infoFailures = 5))
        assertFalse(inventoryIsIncomplete(queryFailed = false, launcherCandidates = 10, infoFailures = 4))
    }

    @Test
    fun `真实批量卸载不触发作废 装卸事件必须照常上报`() {
        // 已卸载的包根本不会出现在 queryIntentActivities 结果里：
        // 候选数从 30 降到 10，但 query 成功、每个候选都取得到详情 → 必须判定为完整。
        assertFalse(inventoryIsIncomplete(queryFailed = false, launcherCandidates = 10, infoFailures = 0))
    }

    @Test
    fun `异常入参不抛异常且不会误判`() {
        assertFalse(inventoryIsIncomplete(queryFailed = false, launcherCandidates = -1, infoFailures = -1))
        assertFalse(inventoryIsIncomplete(queryFailed = false, launcherCandidates = 0, infoFailures = 99))
    }
}
