package com.padguard.child.permission

import android.Manifest
import com.padguard.core.common.Logger
import com.padguard.core.data.repository.AgreementRepository
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.engine.admin.DeviceAdminBridge
import com.padguard.core.engine.admin.OpResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 授权即授予：孩子点击「同意并授权」后的统一入口（说明书 §5.3 + §7）。
 *
 * 职责：
 * 1. 经 Device Owner / Profile Owner 特权，把 §7 的全部运行时权限一次性置为「已授予」，
 *    不弹任何系统授权框；
 * 2. 应用 §11 加固基线（禁 USB 调试 / 防恢复出厂 / 禁安全模式 / 禁未知来源 / 禁模拟位置 / 禁改时间 / 关 USB 文件传输）；
 * 3. 持久化协议版本（DataStore）并写入合规表（Room + 行为日志），供管控端查询「已阅已同意」。
 *
 * 非 DO/PO 设备上 [DeviceAdminBridge.grantRuntimePermission] 返回 [OpResult.Unsupported]，
 * 这是预期的降级：权限无法自动授予时，由服务端/管控端知悉并在 UI 引导走系统授权，
 * 但协议同意本身仍照常持久化（孩子已明确同意）。
 */
@Singleton
class PermissionGranter @Inject constructor(
    private val admin: DeviceAdminBridge,
    private val authRepository: AuthRepository,
    private val agreementRepository: AgreementRepository
) {

    /** §7 运行时权限清单（与 AndroidManifest 声明一一对应）。 */
    val requiredPermissions: List<String> = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    data class GrantReport(
        val granted: List<String> = emptyList(),
        val failed: List<String> = emptyList(),
        val unsupported: List<String> = emptyList(),
        val hardening: List<Pair<String, OpResult>> = emptyList()
    ) {
        val allOk: Boolean get() = failed.isEmpty()
    }

    suspend fun grantAll(version: String = AuthRepository.CURRENT_AGREEMENT_VERSION): GrantReport =
        withContext(Dispatchers.Default) {
            val granted = mutableListOf<String>()
            val failed = mutableListOf<String>()
            val unsupported = mutableListOf<String>()

            for (perm in requiredPermissions) {
                when (val r = admin.grantRuntimePermission(perm)) {
                    is OpResult.Ok -> granted += perm
                    is OpResult.Unsupported -> unsupported += perm
                    is OpResult.Failed -> failed += perm
                }
            }

            val hardening = admin.applyHardeningBaseline()

            // 孩子已明确同意 → 无论授予结果如何都持久化 + 合规留痕
            val sn = runCatching { authRepository.getDeviceSn() }.getOrDefault("")
            val snMask = if (sn.length >= 4) sn.takeLast(4) else sn
            val deviceId = runCatching { authRepository.getDeviceId() }.getOrDefault("")
            runCatching {
                authRepository.saveAgreement(version)
                agreementRepository.record(version, snMask, deviceId)
            }.onFailure { Logger.e(TAG, it) { "persist agreement failed" } }

            Logger.i(TAG) {
                "grantAll: ok=${granted.size}, unsupported=${unsupported.size}, " +
                    "failed=${failed.size}, hardeningApplied=${hardening.size}"
            }
            GrantReport(granted, failed, unsupported, hardening)
        }

    companion object {
        private const val TAG = "PermissionGranter"
    }
}
