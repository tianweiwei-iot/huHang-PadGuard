package com.padguard.child.monitor

import android.annotation.SuppressLint
import android.content.Context
import com.padguard.child.util.isOwnPackage
import com.padguard.core.common.Logger
import com.padguard.core.transport.http.AppInventoryItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用台账差分器：把"当前装了哪些应用"变成"这一次多了谁、少了谁"（R-P3-10）。
 *
 * ## 为什么不用 PACKAGE_ADDED / PACKAGE_REMOVED 广播
 * 非 DO 设备上，第三方应用注册的隐式广播收不到**别的应用**的安装/卸载事件
 * （Android 8.0 起隐式广播受限，且 `PACKAGE_ADDED` 只对收到者自己可控的场景放行）。
 * DO 设备虽然能监听，但本产品在两档形态下都要有同一份行为，
 * 而台账本来就每 30 分钟采一次（见 `GuardService.syncAppInventory`），
 * 顺手做一次差分的成本是零 —— 不引入新权限、不新增唤醒、两档形态完全一致。
 *
 * ## 基线口径（关键，否则会误报）
 * 基线只记**非系统应用**：
 * - 系统应用会随 OTA / 厂商更新凭空出现或消失，把它们算进差分就是给家长刷无意义的告警；
 * - 自家包（`com.padguard.child*`）隐身/换装同样不该惊动家长。
 *
 * ## 首次观测不出事件
 * 第一次运行（装机 / 清数据后）没有基线，此时"当前全部应用"都会被算成"新装"，
 * 一口气推几十条告警等于把家长的信箱炸掉。首次只建立基线，不产生事件。
 *
 * ## 采集失败的保护（两层）
 * 第一层在采集侧：[AppInventoryCollector] 判定本轮不完整（启动器意图解析抛异常、
 * 或大面积取不到 PackageInfo）时直接返回空列表，调用方 `syncAppInventory()` 据此
 * **整轮跳过**——残缺清单根本进不到这里，也不会上传给服务端误标卸载。
 *
 * 第二层在差分侧：部分 ROM 上包可见性 / 意图解析被限制会让台账整体返回空。
 * 若据此判定"所有应用都被卸载"，会瞬间推出一堆假的卸载告警，
 * 因此：当前为空而基线非空时**直接跳过**，既不产事件、也不覆盖基线。
 *
 * 基线落盘一律同步 `commit()`：它是"下一次是否产装卸事件"的唯一持久依据，
 * `apply()` 异步刷盘期间进程被 ROM 回收，重启读到旧基线就会重复推送已报过的告警。
 */
@Singleton
class AppInventoryDeltaTracker @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 读改写（读基线→算差分→写基线）的串行锁。
     * 周期采集（[com.padguard.child.service.GuardService.syncAppInventory]）与指令触发
     * （EngineEffect.SyncAppInventory）可能并发进入 [diff]，否则两次都读到旧基线、各自写回，
     * 一次装卸事件被吞、基线被错误推进。
     */
    private val diffMutex = Mutex()

    /**
     * 与上次基线比较，返回本次的变化。
     *
     * 会就地更新基线（除"采集疑似失败"的保护分支外）。因此**每份台账只能调用一次**，
     * 重复调用第二次会得到空变化（基线已经推进）。
     *
     * 基线推进发生在 [onEvents] 正常返回**之后**：上报事件抛错（含协程取消）时基线不动，
     * 下一轮重新检出这批装卸事件。重复上报交给服务端 / 家长端容忍——静默丢失不可容忍
     * （旧写法 diff 内先写基线、调用方再上报，上报一失败这批事件就永久丢了，R37）。
     */
    suspend fun diff(
        apps: List<AppInventoryItem>,
        onEvents: suspend (AppInventoryChange) -> Unit = {}
    ): AppInventoryChange = withContext(Dispatchers.IO) {
        // 读改写必须串行（见 diffMutex 注释）：避免周期采集与指令触发并发导致装卸事件丢失。
        diffMutex.withLock {
            // 只跟踪"孩子能自己装卸的普通应用"：系统应用与自家包都不参与差分。
            val tracked = apps.filter {
                it.isSystem != true && !isOwnPackage(it.packageName)
            }
            val current = tracked.mapTo(LinkedHashSet()) { it.packageName }
            val previous = readBaseline()

            // 采集疑似整体失败：不判定、不推进基线（见类注释"采集失败的保护"）。
            if (current.isEmpty() && previous != null && previous.isNotEmpty()) {
                Logger.w(TAG) { "inventory looks empty while baseline has ${previous.size}, skip delta" }
                return@withLock AppInventoryChange.EMPTY
            }

            val delta = diffInventoryNames(previous, current)
            val change = AppInventoryChange(
                added = delta.added.mapNotNull { name -> tracked.firstOrNull { it.packageName == name } },
                removed = delta.removed,
                baselineOnly = delta.baselineOnly
            )
            // 事件消费必须先于基线落盘：onEvents 抛错（DB 故障 / 协程取消）时基线保持旧值，
            // 这批装卸事件下一轮会被重新检出并再次上报。
            if (!change.baselineOnly) onEvents(change)
            writeBaseline(current)
            change
        }
    }

    private fun readBaseline(): Set<String>? {
        val raw = prefs.getString(KEY_BASELINE, null) ?: return null
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNullTo(LinkedHashSet()) { arr.optString(it).takeIf(String::isNotBlank) }
        }.onFailure { Logger.w(TAG, it) { "baseline parse failed, treated as first observation" } }
            .getOrNull()
    }

    // 同步刷盘是刻意的（见类注释）：基线是告警判定的持久状态，不是普通 UI 偏好。
    @SuppressLint("ApplySharedPref")
    private fun writeBaseline(names: Set<String>) {
        runCatching {
            val json = JSONArray().apply { names.forEach { put(it) } }.toString()
            prefs.edit().putString(KEY_BASELINE, json).commit()
        }.onFailure { Logger.w(TAG, it) { "baseline write failed" } }
    }

    private companion object {
        const val TAG = "AppInventoryDelta"
        const val PREFS_NAME = "padguard_app_inventory"
        const val KEY_BASELINE = "baseline_packages"
    }
}

/** 一次台账差分的结果。 */
data class AppInventoryChange(
    /** 新增的应用（带名称等元信息，便于直接组装告警文案） */
    val added: List<AppInventoryItem>,
    /** 消失的包名（应用已经不在了，拿不到元信息，只能有包名） */
    val removed: List<String>,
    /** true = 本次只是建立基线，未产生任何变化 */
    val baselineOnly: Boolean
) {
    companion object {
        val EMPTY = AppInventoryChange(emptyList(), emptyList(), baselineOnly = false)
    }
}

/** 纯函数层的差分结果，只关心包名，便于 JVM 单测。 */
internal data class InventoryNameDelta(
    val added: List<String>,
    val removed: List<String>,
    val baselineOnly: Boolean
)

/**
 * 台账包名差分（纯函数，无 Android / IO 依赖）。
 *
 * @param previous 上次基线；null 表示**首次观测**（此时不出事件，只建立基线）
 * @param current 本次采集到的包名集合
 */
internal fun diffInventoryNames(previous: Set<String>?, current: Set<String>): InventoryNameDelta {
    if (previous == null) return InventoryNameDelta(emptyList(), emptyList(), baselineOnly = true)
    return InventoryNameDelta(
        added = (current - previous).sorted(),
        removed = (previous - current).sorted(),
        baselineOnly = false
    )
}
