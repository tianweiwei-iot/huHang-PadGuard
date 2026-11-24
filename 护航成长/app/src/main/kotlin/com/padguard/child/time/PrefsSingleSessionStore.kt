package com.padguard.child.time

import android.content.Context
import com.padguard.core.common.Logger
import com.padguard.core.engine.enforcer.SingleSession
import com.padguard.core.engine.enforcer.SingleSessionStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SingleSessionStore] 的 SharedPreferences 实现。
 *
 * 独立一个 `padguard_session` 文件，而不是塞进 `padguard_time`：
 * 两者生命周期不同（时间护栏每次开机要重建，会话快照要跨开机保留），
 * 混在一个文件里互相覆盖过一次就再难查；独立文件的代价只是多一次很小的 IO。
 *
 * ## 编码格式
 * 用 `putStringSet` 存多条记录，每条 `包名|日键|累计毫秒|touch墙钟|touch单调`。
 * 选它而不是 JSON 的两个理由：
 * 1. **一次原子写入**。分两次 `apply()` 会让进程恰好在中间被杀时读到半新半旧的组合
 *    （累计是新的、touch 是旧的）→ 恢复时算出"离开了很久" → 又是一次免费额度刷新。
 *    `putStringSet` 是单键写入，要么全新要么全旧。
 * 2. 包名的合法字符集不含 `|`（`[a-zA-Z0-9._]`），字段间用分隔符拼接不会有歧义，
 *    不需要为此引入序列化依赖。
 *
 * ## 读失败/坏记录
 * [loadAll] 外层 `runCatching` + 单条 `mapNotNull`：整份读取异常返回空表，
 * **单条解析失败只丢那一条**。一条坏记录让另外三款应用的单次限额一起失效，
 * 是把小故障放大成管控失效 —— 与 `JsonLinesFile` 的坏行自愈是同一原则。
 */
@Singleton
class PrefsSingleSessionStore @Inject constructor(
    @ApplicationContext private val context: Context
) : SingleSessionStore {

    override fun loadAll(): List<SingleSession> {
        val raw = runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_SESSIONS, emptySet())
                .orEmpty()
        }.onFailure {
            Logger.w(TAG) { "load failed: ${it.message}" }
        }.getOrDefault(emptySet())

        return raw.mapNotNull { line ->
            runCatching { decode(line) }.getOrNull()
        }
    }

    override fun saveAll(sessions: List<SingleSession>) {
        runCatching {
            val encoded = sessions.map { encode(it) }.toSet()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putStringSet(KEY_SESSIONS, encoded)
                // commit() 而非 apply()：这是每应用单次使用时长的唯一持久账本（离线判"该应用今日已用多久"的来源）。
                // 状态转移类落盘按项目红线一律用 commit()——apply() 异步提交时若进程恰在刷盘前被强杀，
                // 已累计的时长会丢，孩子凭一次精准崩溃即可多获该应用一拍额度（fail-open 方向）。
                // 本方法仅在会话内容变化时被 AppLimitEnforcer.persistSession 调用（非每 tick 必写），
                // 且调用链在守护主循环（Dispatchers.Default 后台线程），同步写盘不触发 ANR。
                .commit()
        }.onFailure {
            Logger.w(TAG) { "save failed: ${it.message}" }
        }
    }

    /** 编码一条记录；字段顺序必须与 [decode] 严格一致 */
    private fun encode(s: SingleSession): String =
        listOf(
            s.packageName,
            s.dayKey,
            s.accumulatedMs.toString(),
            s.touchWallMs.toString(),
            s.touchMonoMs.toString()
        ).joinToString(SEP)

    /** 解析一条记录；字段数或数字格式不对直接抛，由调用方按单条丢弃 */
    private fun decode(line: String): SingleSession {
        val parts = line.split(SEP)
        require(parts.size == 5) { "malformed session record: $line" }
        return SingleSession(
            packageName = parts[0],
            dayKey = parts[1],
            accumulatedMs = parts[2].toLong(),
            touchWallMs = parts[3].toLong(),
            touchMonoMs = parts[4].toLong()
        )
    }

    private companion object {
        const val TAG = "SingleSessionStore"
        const val PREFS = "padguard_session"
        const val KEY_SESSIONS = "sessions"
        const val SEP = "|"
    }
}
