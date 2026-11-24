package com.padguard.parent.data.remote

import com.padguard.parent.domain.repository.AgeBand
import com.padguard.parent.domain.repository.AgeBandDefault
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * [StaticConfigCache] 行为测试：single-flight / 失败不缓存 / 主动失效。
 *
 * 该类修的是"age-bands 请求风暴"事故，核心承诺有三：
 * 1. 并发首访共享同一次回源（single-flight）；
 * 2. 回源失败不写缓存，下次照常重试（避免把空结果缓存 12 小时）；
 * 3. [StaticConfigCache.invalidate] 后必定重新回源。
 */
class StaticConfigCacheTest {

    private fun fakeBands(limit: Int = 60) =
        mapOf(AgeBand.BAND_8_12 to AgeBandDefault(dailyLimitMinutes = limit))

    @Test
    fun `缓存命中时不再回源`() = runBlocking {
        val cache = StaticConfigCache()
        val calls = AtomicInteger(0)
        val first = cache.ageBandDefaults { calls.incrementAndGet(); Result.success(fakeBands()) }
        val second = cache.ageBandDefaults { calls.incrementAndGet(); Result.success(fakeBands(999)) }

        assertTrue(first.isSuccess)
        assertTrue(second.isSuccess)
        assertEquals(1, calls.get())
        assertSame(first.getOrThrow(), second.getOrThrow())
    }

    @Test
    fun `回源失败不写缓存 下次调用重新回源`() = runBlocking {
        val cache = StaticConfigCache()
        val calls = AtomicInteger(0)

        val failed = cache.ageBandDefaults { calls.incrementAndGet(); Result.failure(IllegalStateException("net down")) }
        assertTrue(failed.isFailure)

        val retry = cache.ageBandDefaults { calls.incrementAndGet(); Result.success(fakeBands()) }
        assertTrue(retry.isSuccess)
        assertEquals(2, calls.get())
    }

    @Test
    fun `invalidate 后必定重新回源`() = runBlocking {
        val cache = StaticConfigCache()
        val calls = AtomicInteger(0)
        cache.ageBandDefaults { calls.incrementAndGet(); Result.success(fakeBands()) }
        cache.invalidate()
        val after = cache.ageBandDefaults { calls.incrementAndGet(); Result.success(fakeBands(120)) }

        assertEquals(2, calls.get())
        assertEquals(120, after.getOrThrow()[AgeBand.BAND_8_12]?.dailyLimitMinutes)
    }

    @Test
    fun `并发首访共享同一次回源 single-flight`() = runBlocking {
        val cache = StaticConfigCache()
        val calls = AtomicInteger(0)
        val d1 = async { cache.ageBandDefaults { calls.incrementAndGet(); Result.success(fakeBands()) } }
        val d2 = async { cache.ageBandDefaults { calls.incrementAndGet(); Result.success(fakeBands()) } }
        val r1 = d1.await()
        val r2 = d2.await()

        // Mutex 串行化取缓存/回源：第二个协程进锁后看到缓存已填充，直接复用
        assertTrue(r1.isSuccess)
        assertTrue(r2.isSuccess)
        assertEquals(1, calls.get())
        assertSame(r1.getOrThrow(), r2.getOrThrow())
    }
}
