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
 * - 解密失败（密钥丢失或数据被篡改）时**不静默降级为默认策略**，
 *   而是抛出 [PolicyTamperedException]，由上层上报高危告警并强制重新拉取，
 *   避免"策略被破坏后终端处于无管控状态"这一致命漏洞。
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
        entity?.let { decode(it) } ?: PolicyPackage.default()
    }

    suspend fun getPolicy(): PolicyPackage =
        dao.get()?.let { decode(it) } ?: PolicyPackage.default()

    suspend fun currentVersion(): Int = dao.get()?.version ?: 0

    /**
     * 保存服务端下发的全量策略包。
     * @return 实际落地的策略（可能与传入不同，说明发生了局部合并）
     */
    suspend fun save(policy: PolicyPackage, rawJson: String? = null): PolicyPackage {
        val merged = getPolicy().mergeWith(policy)
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

    private fun decode(entity: PolicyEntity): PolicyPackage {
        return try {
            json.decodeFromString(PolicyPackage.serializer(), keystore.decryptFromBase64(entity.encryptedJson))
        } catch (e: Exception) {
            Logger.e("PolicyRepository", e) { "failed to decrypt policy, treating as tampered" }
            throw PolicyTamperedException("本地策略解密失败，可能被篡改或密钥失效", e)
        }
    }
}

/** 策略包解密失败：说明本地缓存被破坏，需上报高危告警并强制重新拉取 */
class PolicyTamperedException(message: String, cause: Throwable? = null) : Exception(message, cause)
