package com.padguard.parent.data.remote

import com.padguard.domain.repository.AgeBand
import com.padguard.domain.repository.AgeBandDefault
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服务端「静态配置」的进程内缓存。
 *
 * ## 背景：这个 bug 是怎么产生的
 * `GET /v1/policies/age-bands` 返回的是服务端**代码里的常量**（各龄档合规默认值），
 * 只有重新部署服务端才会变。但它在端上被当作普通接口随页面刷新反复拉取：
 * - [com.padguard.parent.presentation.viewmodel.HomeViewModel] 每 **10 秒**轮询一次
 *   （`loadDailyLimit` 一次并发拉 3 个接口，其中就含 age-bands）；
 * - 设备详情页、时间管控页进入时又各拉一次。
 * 于是同一秒内服务端能收到十几次 `age-bands` 请求——服务端日志里"并发请求风暴"的来源。
 *
 * 请求数一多，真正的业务请求（设备列表、统计）就要和它在 OkHttp
 * **同主机并发上限（默认 5）** 里排队，家长端的观感就是"点哪里都转圈"。
 *
 * ## 为什么用「缓存 + 单飞」而不是只加缓存
 * 只加 TTL 缓存挡不住**冷启动瞬间**的并发：三个页面同时首次请求，缓存都还没填充，
 * 仍会一起打到网络。这里用 [Mutex] 串行化"取缓存 / 回源"，
 * 第二个协程加锁成功后会看到缓存已由第一个协程填充，从而复用结果（即 single-flight）。
 *
 * ## 为什么失败不写缓存
 * 网络失败属于暂时故障，若把空结果缓存下来，会导致一段时间内该配置永久取不到。
 * 失败直接返回，由调用方按现有逻辑兜底（本地 `DEFAULT_AGE_BANDS`）。
 */
@Singleton
class StaticConfigCache @Inject constructor() {

    private val mutex = Mutex()

    @Volatile
    private var ageBands: Map<AgeBand, AgeBandDefault>? = null

    @Volatile
    private var ageBandsFetchedAt: Long = 0L

    /**
     * 取分龄默认值：命中缓存直接返回；否则回源一次，并发请求共享同一个回源结果。
     *
     * @param fetch 真正的网络请求（仅在缓存缺失或过期时调用）
     */
    suspend fun ageBandDefaults(
        fetch: suspend () -> Result<Map<AgeBand, AgeBandDefault>>
    ): Result<Map<AgeBand, AgeBandDefault>> {
        ageBands?.let { cached ->
            if (!isExpired(ageBandsFetchedAt)) return Result.success(cached)
        }

        return mutex.withLock {
            // 二次检查：等待锁期间可能已有协程完成回源（single-flight 的关键）
            ageBands?.let { cached ->
                if (!isExpired(ageBandsFetchedAt)) return@withLock Result.success(cached)
            }

            val result = fetch()
            result.onSuccess { fetched ->
                ageBands = fetched
                ageBandsFetchedAt = System.currentTimeMillis()
            }
            result
        }
    }

    /** 服务端配置变更（或切换服务端地址）后主动失效，下次请求重新回源 */
    fun invalidate() {
        ageBands = null
        ageBandsFetchedAt = 0L
    }

    private fun isExpired(fetchedAt: Long): Boolean =
        System.currentTimeMillis() - fetchedAt >= AGE_BAND_TTL_MS

    private companion object {
        /**
         * 分龄默认值的缓存时长。
         * 该配置是服务端常量，变更必须伴随服务端重新部署，极少发生；
         * 取 12 小时既覆盖"App 长时间在前台"的场景，也保证跨天/重启后能拿到最新值。
         */
        const val AGE_BAND_TTL_MS = 12 * 60 * 60 * 1000L
    }
}
