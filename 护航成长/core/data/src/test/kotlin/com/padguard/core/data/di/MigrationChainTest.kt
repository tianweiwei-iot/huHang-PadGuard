package com.padguard.core.data.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Room 迁移链守卫（P1-7）。
 *
 * 背景：历史上 1→2、2→3 两个升级步骤没有显式迁移，老库升级只能触发
 * destructive 清库——app_usage 当日累计时长归零，等于白送一段绕过限额的
 * 使用时间（触碰 A1/A3）。本测试锁死"升级链无断点"这一核心保证：
 * 任何存量版本号 1..5 都能一路显式迁移到最新版，不落入 destructive。
 *
 * 迁移 SQL 本身的正确性由 Room 打库时的 schema 校验兜底 + 真机回归
 * （见 docs/全面测试评估与整改计划-2026-11-18.md 未闭环清单）；
 * 纯 JVM 环境无法启动真库，这里只测可行为化的链完整性。
 */
class MigrationChainTest {

    @Test
    fun `migration chain covers every version from 1 to latest without gaps`() {
        val latest = 5
        val sorted = PADGUARD_MIGRATIONS.sortedBy { it.startVersion }

        assertEquals("升级链必须从 v1 起（最早装机即 v1）", 1, sorted.first().startVersion)
        assertEquals("升级链必须到达当前 schema 版本 $latest", latest, sorted.last().endVersion)

        // 相邻步骤连续：1→2→3→4→5，任何断点都是一次静默清库
        for (i in 1 until sorted.size) {
            assertEquals(
                "升级链在 v${sorted[i - 1].endVersion}→v${sorted[i].startVersion} 处断裂：" +
                    "该版本的老库升级将触发 destructive 整库重建（当日时长归零=绕过限额）",
                sorted[i - 1].endVersion, sorted[i].startVersion
            )
        }

        // 无重复步骤：重复的 (start,end) 说明有人复制迁移忘了改版本号（正是 3→4 错标的历史成因）
        val pairs = PADGUARD_MIGRATIONS.map { it.startVersion to it.endVersion }
        assertEquals("存在重复的迁移步骤：$pairs", pairs.size, pairs.distinct().size)
    }

    @Test
    fun `migration 3_4 exists and stays in chain`() {
        // v3→v4 曾经错标为 ADD COLUMN（v3 已含该列，真实升级会 duplicate column 崩溃），
        // 现为防御式幂等实现；此用例确保该步骤不被误删（删了链就断，老库升级即清库）。
        val m34 = PADGUARD_MIGRATIONS.firstOrNull { it.startVersion == 3 && it.endVersion == 4 }
        assertNotNull("v3→v4 步骤缺失会破坏链完整性", m34)
        assertTrue("v3→v4 步骤必须在链中", PADGUARD_MIGRATIONS.map { it.startVersion to it.endVersion }.contains(3 to 4))
    }
}
