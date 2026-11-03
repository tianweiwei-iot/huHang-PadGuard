package com.padguard.core.data.repository

import com.padguard.core.common.Logger
import com.padguard.core.data.crypto.KeystoreManager
import com.padguard.core.data.db.PolicyDao
import com.padguard.core.data.db.PolicyEntity
import com.padguard.core.data.model.policy.PolicyPackage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 策略包本地缓存仓库（对应说明书「离线管控落地逻辑」）。
 *
 * 关键设计：
 * - 策略包 AES-GCM 加密落库，用户/第三方工具无法读取或篡改管控规则。
 * - 解密失败（密钥丢失或数据被篡改）时**在本层就地兜底**：清掉坏行、按默认策略继续，
 *   并记 ERROR 日志触发下一次全量拉取。
 *   早期实现是抛 [PolicyTamperedException] 交给上层，但全工程没有任何一处 catch ——
 *   `observePolicy()` 的 6 个收集点（GuardService / HomeViewModel / AppInventoryViewModel /
 *   ProfileViewModel / UsageStatusProvider / LocationAutoReporter）会直接抛穿收集协程，
 *   走默认 CoroutineExceptionHandler 把进程打崩；进程被守护拉起后又崩，形成崩溃循环。
 *   "不静默降级"的初衷是好的，但用崩溃换"不降级"是不可接受的取舍：
 *   清空缓存后服务端会在下一轮同步重新下发，管控恢复；崩溃则意味着彻底不可用。
 */
@Singleton
class PolicyRepository @Inject constructor(
    private val dao: PolicyDao,
    private val keystore: KeystoreManager
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    /** 观察当前生效策略（未绑定时返回默认策略） */
    fun observePolicy(): Flow<PolicyPackage> = dao.observe().map { entity ->
        (entity?.let { decodeOrRecover(it) } ?: PolicyPackage.default()).deriveTimeControl()
    }

    suspend fun getPolicy(): PolicyPackage =
        (dao.get()?.let { decodeOrRecover(it) } ?: PolicyPackage.default()).deriveTimeControl()

    suspend fun currentVersion(): Int = dao.get()?.version ?: 0

    /**
     * 保存服务端下发的全量策略包。
     * @return 实际落地的策略（可能与传入不同，说明发生了局部合并）
     */
    suspend fun save(policy: PolicyPackage, rawJson: String? = null): PolicyPackage {
        val merged = getPolicy().mergeWith(policy).deriveTimeControl()
        val payload = rawJson ?: json.encodeToString(PolicyPackage.serializer(), policy)
        dao.upsert(
            PolicyEntity(
                version = merged.version,
                encryptedJson = keystore.encryptToBase64(payload),
                updatedAt = System.currentTimeMillis(),
                source = PolicyEntity.SOURCE_REMOTE
            )
        )
        Logger.i("PolicyRepository") { "policy saved, version=${merged.version}" }
        return merged
    }

    /** 重置为默认策略（仅用于解绑或服务端下发 RESET_POLICY 指令） */
    suspend fun reset() {
        dao.clear()
    }

    /**
     * 解密；失败时清掉坏行并返回 null（调用方回落默认策略）。
     *
     * 清行是必要动作：不清的话这一行每次读取都会反复失败，
     * 而 `dao.clear()` 会让 observe 流重新发射一次空 → 默认策略，管控链路不至于断。
     */
    private suspend fun decodeOrRecover(entity: PolicyEntity): PolicyPackage? {
        return try {
            json.decodeFromString(PolicyPackage.serializer(), keystore.decryptFromBase64(entity.encryptedJson))
        } catch (e: Exception) {
            Logger.e("PolicyRepository", e) {
                "策略解密失败（密钥失效或缓存被破坏），清空本地缓存并回落默认策略，等待服务端重新下发"
            }
            runCatching { dao.clear() }
                .onFailure { Logger.e("PolicyRepository", it) { "清空损坏策略行失败" } }
            null
        }
    }
}
