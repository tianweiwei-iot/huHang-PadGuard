package com.padguard.core.data.repository

import com.padguard.core.data.db.AgreementDao
import com.padguard.core.data.db.AgreementEntity
import com.padguard.core.data.model.LogType
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 使用授权协议合规仓库（说明书 §5.4 / §10.1）。
 *
 * 「同意并授权」是两件事的硬凭证：
 * 1. 本地 Room 表留痕（[AgreementEntity]），供管控端查询「已阅已同意」；
 * 2. 行为日志队列追加一条 [LogType.AGREEMENT]，随心跳/批量上报服务端。
 *
 * 两条记录都只存脱敏 SN 后 4 位与协议版本，原始硬件标识不出端。
 */
@Singleton
class AgreementRepository @Inject constructor(
    private val agreementDao: AgreementDao,
    private val logRepository: LogRepository
) {

    /** 写入一次协议签署记录并上报合规日志。 */
    suspend fun record(version: String, snMask: String, deviceId: String) {
        agreementDao.insert(
            AgreementEntity(
                version = version,
                signedAt = System.currentTimeMillis(),
                snMask = snMask,
                deviceId = deviceId
            )
        )
        logRepository.append(
            type = LogType.AGREEMENT,
            payload = mapOf(
                "version" to version,
                "signedAt" to System.currentTimeMillis().toString(),
                "snMask" to snMask
            )
        )
    }

    /** 最近签署记录，UI 可据此展示「已同意 vX.X.X」。 */
    fun observeLatest(limit: Int = 1): Flow<List<AgreementEntity>> =
        agreementDao.observeRecent(limit)
}
