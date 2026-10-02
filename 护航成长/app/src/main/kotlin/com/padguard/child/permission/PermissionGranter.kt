package com.padguard.child.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.padguard.core.common.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationContext private val context: Context,
    private val admin: DeviceAdminBridge,
    private val authRepository: AuthRepository,
    private val agreementRepository: AgreementRepository
) {

    /**
     * §7 运行时权限清单（与 AndroidManifest 声明一一对应）。
     *
     * 已按上架合规要求瘦身：短信、通话记录、电话状态全部移除 —— 它们与管控能力无关，
     * 却是 Google Play 专项申报与国内工信部整治的高危项。
     * 设备标识由服务端在绑定时下发，不依赖 IMEI。
     */
    val requiredPermissions: List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_EXTERNAL_STORAGE)
        add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        // 通知权限（Android 13+）必须一起授予。
        // 缺了它，孩子端连"点击开启屏幕采集"的引导通知都发不出来 ——
        // 而实时看屏在没有 MediaProjection 令牌时**只能**靠这条通知把授权页带起来，
        // 结果是家长端一直停在"连接中"，且平板上看不到任何提示，排查时极难定位。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

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
        Manifest.permission.CAMERA -> "相机"
        Manifest.permission.RECORD_AUDIO -> "麦克风"
        Manifest.permission.READ_EXTERNAL_STORAGE -> "读取存储"
        Manifest.permission.WRITE_EXTERNAL_STORAGE -> "写入存储"
        Manifest.permission.POST_NOTIFICATIONS -> "通知"
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
        persistAgreement(version)
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

    /**
     * 开机/服务重启后的自愈补授：只把**当前缺失**的运行时权限重新置为已授予。
     *
     * 与 [grantAll] 的区别：不写协议、不做加固基线（那些是一次性的，重复执行没意义还拖慢启动），
     * 因此可以被守护服务在每次 bootstrap 时无副作用地调用。
     *
     * 为什么必须有这一步：DO 的静默授予只在孩子点「同意并授权」那一次执行过。
     * 之后只要重装应用（覆盖安装会重置运行时权限）或系统清掉授予状态，
     * 权限就永久丢失 —— 表现最明显的就是通知权限缺失：
     * 屏幕采集的引导通知孩子根本收不到，家长端一直转圈"连接中"，
     * 而平板上没有任何提示，从现象完全看不出是权限问题。
     */
    suspend fun ensureGranted(): Int = withContext(Dispatchers.Default) {
        val missing = requiredPermissions.filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return@withContext 0
        var ok = 0
        for (perm in missing) {
            when (admin.grantRuntimePermission(perm)) {
                is OpResult.Ok -> ok++
                is OpResult.Failed -> Logger.w(TAG) { "re-grant failed: $perm" }
                is OpResult.Unsupported -> Unit
            }
        }
        Logger.i(TAG) { "ensureGranted: missing=${missing.size}, reGranted=$ok" }
        ok
    }

    /**
     * 持久化监护协议（孩子已明确「同意并授权」后的合规留痕）。
     *
     * 抽成独立方法，供两类授权入口共用，确保"同意"在任意路径下只落一次、且必定留痕：
     * - DO/PO 路径：[grantAllWithProgress] 内部已调用；
     * - 非 DO 路径：[PermissionOnboardingViewModel] 在系统运行时权限弹窗结果回来后调用。
     *
     * 与具体权限是否授予成功解耦：孩子已点击"一键确认授权"即视为明确同意，
     * 即使部分系统权限被拒/降级，协议同意本身仍照常落库（供管控端查询"已阅已同意"）。
     */
    suspend fun persistAgreement(version: String = AuthRepository.CURRENT_AGREEMENT_VERSION) {
        val sn = runCatching { authRepository.getDeviceSn() }.getOrDefault("")
        val snMask = if (sn.length >= 4) sn.takeLast(4) else sn
        val deviceId = runCatching { authRepository.getDeviceId() }.getOrDefault("")
        runCatching {
            authRepository.saveAgreement(version)
            agreementRepository.record(version, snMask, deviceId)
        }.onFailure { Logger.e(TAG, it) { "persist agreement failed" } }
    }

    companion object {
        private const val TAG = "PermissionGranter"
    }
}
