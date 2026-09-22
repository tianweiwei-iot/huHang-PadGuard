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

    /**
     * 单条授权步骤的 UI 状态（驱动前端进度条）。
     *
     * [key] 与 [PermissionGranter] 内部步骤键一致，UI 按 key 增量更新；
     * [state] 区分 待执行 / 执行中 / 已授予 / 当前模式不支持 / 失败。
     */
    data class GrantStep(
        val key: String,
        val label: String,
        val state: State
    ) {
        enum class State { PENDING, RUNNING, DONE, UNSUPPORTED, FAILED }
    }

    /** 全部步骤键（运行时权限键 + 加固基线键 + 协议持久化），供 UI 预置完整进度列表。 */
    val stepKeys: List<Pair<String, String>> by lazy {
        val runtime = requiredPermissions.map { it to labelOf(it) }
        val hardening = listOf(
            "usbDebug" to "禁用 USB 调试",
            "factoryReset" to "禁止恢复出厂设置",
            "safeBoot" to "禁止安全模式",
            "unknownSources" to "禁止未知来源安装",
            "configDateTime" to "锁定系统时间",
            "usbDataSignaling" to "关闭 USB 文件传输"
        )
        runtime + hardening + listOf("agreement" to "持久化监护协议")
    }

    /** 权限键 → 中文展示名（与 AndroidManifest 声明对应）。 */
    fun labelOf(perm: String): String = when (perm) {
        Manifest.permission.ACCESS_FINE_LOCATION -> "精确定位"
        Manifest.permission.ACCESS_COARSE_LOCATION -> "大致定位"
        Manifest.permission.READ_PHONE_STATE -> "电话状态"
        Manifest.permission.SEND_SMS -> "发送短信"
        Manifest.permission.READ_SMS -> "读取短信"
        Manifest.permission.RECEIVE_SMS -> "接收短信"
        Manifest.permission.READ_CALL_LOG -> "通话记录"
        Manifest.permission.CAMERA -> "相机"
        Manifest.permission.RECORD_AUDIO -> "麦克风"
        Manifest.permission.READ_EXTERNAL_STORAGE -> "读取存储"
        Manifest.permission.WRITE_EXTERNAL_STORAGE -> "写入存储"
        else -> perm.substringAfterLast('.')
    }

    /**
     * 带逐条进度回调的静默授予（说明书 §5.3 / §7 / §11）。
     *
     * 仅在 Device Owner / Profile Owner 下全部步骤可真正静默完成（[onStep] 全程后台推进，
     * 前端只需渲染进度）；非 DO/PO 时运行时权限与加固基线会逐条返回 UNSUPPORTED，
     * [onStep] 仍会如实回调，便于 UI 标记"当前模式不支持"而非假装有进度。
     */
    suspend fun grantAllWithProgress(
        version: String = AuthRepository.CURRENT_AGREEMENT_VERSION,
        onStep: suspend (GrantStep) -> Unit
    ): GrantReport = withContext(Dispatchers.Default) {
        val granted = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val unsupported = mutableListOf<String>()

        for (perm in requiredPermissions) {
            val label = labelOf(perm)
            onStep(GrantStep(perm, label, GrantStep.State.RUNNING))
            when (val r = admin.grantRuntimePermission(perm)) {
                is OpResult.Ok -> {
                    granted += perm
                    onStep(GrantStep(perm, label, GrantStep.State.DONE))
                }
                is OpResult.Unsupported -> {
                    unsupported += perm
                    onStep(GrantStep(perm, label, GrantStep.State.UNSUPPORTED))
                }
                is OpResult.Failed -> {
                    failed += perm
                    onStep(GrantStep(perm, label, GrantStep.State.FAILED))
                }
            }
        }

        val hardening = admin.applyHardeningBaseline()
        for ((key, result) in hardening) {
            val label = labelOfHardening(key)
            val state = when (result) {
                is OpResult.Ok -> GrantStep.State.DONE
                is OpResult.Unsupported -> GrantStep.State.UNSUPPORTED
                is OpResult.Failed -> GrantStep.State.FAILED
            }
            onStep(GrantStep(key, label, state))
        }

        // 协议持久化（孩子已明确同意 → 无论授予结果如何都照常留痕）
        onStep(GrantStep("agreement", "持久化监护协议", GrantStep.State.RUNNING))
        val sn = runCatching { authRepository.getDeviceSn() }.getOrDefault("")
        val snMask = if (sn.length >= 4) sn.takeLast(4) else sn
        val deviceId = runCatching { authRepository.getDeviceId() }.getOrDefault("")
        runCatching {
            authRepository.saveAgreement(version)
            agreementRepository.record(version, snMask, deviceId)
        }.onFailure { Logger.e(TAG, it) { "persist agreement failed" } }
        onStep(GrantStep("agreement", "持久化监护协议", GrantStep.State.DONE))

        Logger.i(TAG) {
            "grantAllWithProgress: ok=${granted.size}, unsupported=${unsupported.size}, " +
                "failed=${failed.size}, hardeningApplied=${hardening.size}"
        }
        GrantReport(granted, failed, unsupported, hardening)
    }

    private fun labelOfHardening(key: String): String = when (key) {
        "usbDebug" -> "禁用 USB 调试"
        "factoryReset" -> "禁止恢复出厂设置"
        "safeBoot" -> "禁止安全模式"
        "unknownSources" -> "禁止未知来源安装"
        "configDateTime" -> "锁定系统时间"
        "usbDataSignaling" -> "关闭 USB 文件传输"
        else -> key
    }

    /** 兼容旧调用方：无进度回调的静默授予。 */
    suspend fun grantAll(version: String = AuthRepository.CURRENT_AGREEMENT_VERSION): GrantReport =
        grantAllWithProgress(version) { }

    companion object {
        private const val TAG = "PermissionGranter"
    }
}
