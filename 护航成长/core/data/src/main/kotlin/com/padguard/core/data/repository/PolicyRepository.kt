package com.padguard.core.data.repository

import android.content.Context
import com.padguard.core.common.Logger
import com.padguard.core.data.crypto.KeystoreManager
import com.padguard.core.data.db.PolicyDao
import com.padguard.core.data.db.PolicyEntity
import com.padguard.core.data.model.policy.PolicyPackage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 策略包本地缓存仓库（对应说明书「离线管控落地逻辑」）。
 *
 * 关键设计：
 * - 策略包 AES-GCM 加密落库，用户/第三方工具无法读取或篡改管控规则。
 * - 解密失败（密钥丢失或数据被篡改）时**在本层就地兜底**，但不崩溃、不删行、不主动放开管控：
 *   保留加密原行、沿用本进程上次成功解码的策略副本，同时把「缓存损坏」上报为版本 0
 *   促使服务端重发（决策见 [PolicyCacheFallback]）。
 *   早期实现是抛 [PolicyTamperedException] 交给上层，但全工程没有任何一处 catch ——
 *   `observePolicy()` 的 6 个收集点（GuardService / HomeViewModel / AppInventoryViewModel /
 *   ProfileViewModel / UsageStatusProvider / LocationAutoReporter）会直接抛穿收集协程，
 *   走默认 CoroutineExceptionHandler 把进程打崩；进程被守护拉起后又崩，形成崩溃循环。
 *
 * ## 不能再「清坏行 + 回落默认策略」（新增缺陷 L）
 * 旧兜底是 `dao.clear()` 后回落 [PolicyPackage.default]。`default()` 是宵禁关、时段规则空、
 * 日额度 0、黑白名单全空的**不管控基线**，且 `observePolicy()` 会把这份 default 回写到
 * `AppGuardPolicyStore` 镜像，连应用管控那份跨进程的好快照也一并覆盖。
 * 「服务端下一轮同步会重新下发」这个前提**只在联网成立**：离线设备一次密钥失效即永久脱管，
 * 且原行已删，连重试解密的机会都没有 —— 直接违反离线红线。现在改为保留坏行 + 沿用副本 +
 * 禁止把不可信策略回写镜像，并把损坏态暴露给服务端让它重发任意版本完成自愈。
 */
@Singleton
class PolicyRepository @Inject constructor(
    private val dao: PolicyDao,
    private val keystore: KeystoreManager,
    @ApplicationContext private val appContext: Context
) {
    companion object {
        private const val STATE_PREFS = "padguard_policy_state"
        private const val KEY_EVER_SAVED = "ever_saved"
    }

    /**
     * 设备上是否**曾经**成功保存过策略（跨进程持久化，冷启动仍记得）。
     *
     * 只靠进程内 lastKnownGood 无法区分「首次安装从未下发」与「已绑定设备被删策略行」：
     * 后者崩溃重启后行缺失就被判成可信 default，等于 ROOT 删一行即可离线解除全部管控。
     * 这个标志让"行丢了"始终按缓存损坏处理：版本上报 0 促使服务端重发，
     * hasTrustedPolicy=false 阻止 default 回写应用管控镜像。
     */
    private val statePrefs by lazy { appContext.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE) }

    @Volatile
    private var everSaved: Boolean = false

    init {
        everSaved = runCatching { statePrefs.getBoolean(KEY_EVER_SAVED, false) }.getOrDefault(false)
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    /**
     * 本进程上一次**成功解码**的策略副本。缓存损坏时管控继续按它执行，强度不下降。
     * 进程内存态即可覆盖「运行中密钥失效」这一最常见场景；冷启动即失效的残余边界见
     * [PolicyCacheFallback.resolve] 与评审报告，未虚构持久化副本能力。
     */
    @Volatile
    private var lastKnownGood: PolicyPackage? = null

    /** 当前策略行是否处于「解不开」状态；驱动镜像抑制、版本上报与重发接受。 */
    @Volatile
    private var decodeFailed: Boolean = false

    /**
     * 是否存在足以**回写**镜像 / 总开关 / 护眼滤镜的可信策略。
     * 由 [com.padguard.child.service.GuardService.observeAppPolicyMirror] 在每帧收集时查询。
     */
    fun hasTrustedPolicy(): Boolean =
        PolicyCacheFallback.shouldMirror(decodeFailed, lastKnownGood)

    /** 观察当前生效策略（从未下发过策略时才返回默认基线） */
    fun observePolicy(): Flow<PolicyPackage> = dao.observe().map { entity -> resolveCurrent(entity) }

    suspend fun getPolicy(): PolicyPackage = resolveCurrent(dao.get())

    /**
     * 一次读取的统一入口：区分「策略行不存在」与「策略行存在但解不开」。
     *
     * 行不存在（解绑 / 库被重建）是**可信的"从未下发"**，必须把损坏态与内存副本一并清掉；
     * 否则残留的 `decodeFailed=true` + 旧 `lastKnownGood` 会让 [hasTrustedPolicy] 误判为可信，
     * 把这一帧本应放开的 default 当成"上次良好策略"回写，或反过来卡住后续同步。
     */
    private suspend fun resolveCurrent(entity: PolicyEntity?): PolicyPackage {
        val rowLost = entity == null && everSaved
        if (entity == null) {
            if (rowLost) {
                // 已下发过却没行：按"缓存损坏"处理，保留好快照、禁止 default 回写镜像、
                // 上报版本 0 让服务端重发。绝不因为删行就放开管控。
                decodeFailed = true
            } else {
                // 真·首次安装：默认基线可信，正常写镜像。
                decodeFailed = false
                lastKnownGood = null
            }
        }
        val decoded = entity?.let { decodeOrRecover(it) }
        return PolicyCacheFallback.resolve(
            entityPresent = entity != null,
            decoded = decoded,
            lastKnownGood = lastKnownGood,
            policyRowLost = rowLost
        ).deriveTimeControl()
    }

    /**
     * 缓存损坏时上报 0：服务端以版本号判断「设备是否已是最新」，坏行里的旧版本号
     * 会让它误判无需重发，设备就永远等不到修复（见 [PolicyCacheFallback.reportedVersion]）。
     */
    suspend fun currentVersion(): Int =
        PolicyCacheFallback.reportedVersion(decodeFailed, dao.get()?.version ?: 0)

    /**
     * 保存服务端下发的全量策略包。
     * @return 实际落地的策略（可能与传入不同，说明发生了局部合并）
     */
    suspend fun save(policy: PolicyPackage, rawJson: String? = null): PolicyPackage {
        val current = getPolicy()
        // 服务端版本不高于本地：**一律以本地为准，且不写库**。
        //
        // 原实现继续往下走：`mergeWith` 对低版本直接返回本地策略（version 取本地、内容也取本地），
        // 但 `payload` 用的是服务端原文 —— 同一行里被拼成"本地的高版本号 + 服务端的旧内容"。
        // 服务端按 `policy.version == currentVersion` 判断设备是否最新，于是此后它会一直认为
        // 设备已是最新：家长后续每一次策略变更都被 `mergeWith` 丢弃，界面显示"已下发"，
        // 孩子端规则永远停在旧版本，且没有任何失败信号（只能靠重装恢复）。
        // 典型触发：服务端库还原/重建、设备策略行被删后由 createDefault 重新生成 v1。
        if (!PolicyCacheFallback.shouldAcceptIncoming(decodeFailed, policy.version, current.version)) {
            Logger.w("PolicyRepository") {
                "ignore stale policy v=${policy.version} (local v=${current.version})"
            }
            return current
        }
        if (decodeFailed) {
            Logger.w("PolicyRepository") {
                "accept re-issued policy v=${policy.version} to repair unreadable cache"
            }
        }
        val merged = current.mergeWith(policy).deriveTimeControl()
        val payload = rawJson ?: json.encodeToString(PolicyPackage.serializer(), policy)
        dao.upsert(
            PolicyEntity(
                version = merged.version,
                encryptedJson = keystore.encryptToBase64(payload),
                updatedAt = System.currentTimeMillis(),
                source = PolicyEntity.SOURCE_REMOTE
            )
        )
        // 写入成功即重新拥有一份可信副本：解除损坏态，恢复常规版本门槛与镜像回写。
        lastKnownGood = merged
        decodeFailed = false
        everSaved = true
        runCatching { statePrefs.edit().putBoolean(KEY_EVER_SAVED, true).apply() }
            .onFailure { Logger.w("PolicyRepository") { "persist policy-saved flag failed: ${it.message}" } }
        Logger.i("PolicyRepository") { "policy saved, version=${merged.version}" }
        return merged
    }

    /** 重置为默认策略（仅用于解绑或服务端下发 RESET_POLICY 指令） */
    suspend fun reset() {
        dao.clear()
        // 解绑是**主动**放开管控，与"缓存读坏了"完全不同：内存副本必须一并清掉，
        // 否则重绑后还会按上一任家长的策略继续管控。
        lastKnownGood = null
        decodeFailed = false
        everSaved = false
        runCatching { statePrefs.edit().putBoolean(KEY_EVER_SAVED, false).apply() }
            .onFailure { Logger.w("PolicyRepository") { "clear policy-saved flag failed: ${it.message}" } }
    }

    /**
     * 解密；失败时**保留加密原行**并沿用上次已知良好副本。
     *
     * 绝不能再 `dao.clear()`：删掉的是设备上唯一一份策略，离线时服务端无法补发，
     * 而 observe 流会立刻发射 default（不管控基线）把管控一起解除。保留坏行的代价只是
     * 每次读取都记一条 ERROR 日志，可控；换来的是密钥恢复 / 服务端重发后仍能自愈。
     */
    private suspend fun decodeOrRecover(entity: PolicyEntity): PolicyPackage? {
        return try {
            val decoded = json.decodeFromString(
                PolicyPackage.serializer(),
                keystore.decryptFromBase64(entity.encryptedJson)
            )
            lastKnownGood = decoded
            decodeFailed = false
            decoded
        } catch (e: Exception) {
            decodeFailed = true
            Logger.e("PolicyRepository", e) {
                "策略解密失败（密钥失效或缓存被破坏）：保留加密原行，沿用上次良好策略，" +
                    "不做 fail-open 降级；已上报版本 0 等待服务端重发"
            }
            lastKnownGood
        }
    }
}
