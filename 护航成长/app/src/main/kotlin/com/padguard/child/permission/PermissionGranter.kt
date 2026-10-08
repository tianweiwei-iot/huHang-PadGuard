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
 * 授权即授予：孩子端（或家长代操作）点击「同意并授权」后的统一入口（说明书 §5.3 + §7）。
 *
 * 职责：
 * 1. 经 Device Owner / Profile Owner 特权，把 §7 的全部运行时权限一次性置为「已授予」，
 *    不弹任何系统授权框；
 * 2. 应用 §11 加固基线（禁 USB 调试 / 防恢复出厂 / 禁安全模式 / 禁未知来源 / 禁模拟位置 / 禁改时间 / 关 USB 文件传输）；
 * 3. 检出哪些项必须由家长在系统设置页手动打开（AppOps / 无障碍），交给上层排队引导；
 * 4. 持久化协议版本（DataStore）并写入合规表（Room + 行为日志），供管控端查询「已阅已同意」。
 *
 * ## 权限清单只有一份
 * 历史上这里并存 `corePermissions`（装机申请）与 `optionalPermissions`（用到再申请），
 * 于是"远程拍照"必须在孩子毫无准备的时候补弹一次相机权限——正是要消灭的二次授权。
 * 现在只有 [requiredPermissions]：装机这一趟全部申请到位，之后 App 内永不补弹。
 *
 * 同理，AppOps 里的"必做/可选"二分也已取消（见 [SpecialPermission]）：
 * 装机一次收走全部项，换来全流程再无授权中断。
 *
 * 非 DO/PO 设备上 [DeviceAdminBridge.grantRuntimePermission] 返回 [OpResult.Unsupported]，
 * 这是预期的降级：静默授权走不通时由系统弹窗申请，但协议同意本身仍照常持久化。
 */
@Singleton
class PermissionGranter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val admin: DeviceAdminBridge,
    private val authRepository: AuthRepository,
    private val agreementRepository: AgreementRepository
) {

    /**
     * 全部运行时权限清单（与 AndroidManifest 声明一一对应）。
     *
     * 已按上架合规要求瘦身：短信、通话记录、电话状态全部移除 —— 它们与管控能力无关，
     * 却是 Google Play 专项申报与国内工信部整治的高危项。
     * 设备标识由服务端在绑定时下发，不依赖 IMEI。
     *
     * 这份清单同时用于三件事，保证口径单一：
     * - 系统弹窗一次性申请（非 DO 机型）；
     * - DO/PO 的静默授予；
     * - [ensureGranted] 开机/服务重启后的自愈补授。
     */
    val requiredPermissions: List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        // 相机 / 麦克风：远程拍照、远程听音需要。
        // 装机时一并申请，避免家长第一次下发"看看孩子在干什么"时
        // 平板这边弹出一个孩子看得见的权限框——那时场景已经失真了。
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        // Android 13(API33)+ 起分区存储已取代这两个权限，且 manifest 里声明了 maxSdk=32。
        // 在更高版本上仍去请求，系统只会当成"未声明的权限"直接拒绝，
        // 表现为家长看到"已跳过：读取存储、写入存储"，可实际上没有任何功能受影响 ——
        // 纯粹是多弹一次窗 + 多一条吓人的提示。按版本过滤掉。
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S) {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        // 通知权限（Android 13+）：缺了它，孩子端连"点击开启屏幕采集"的引导通知都发不出来 ——
        // 而实时看屏在没有 MediaProjection 令牌时**只能**靠这条通知把授权页带起来，
        // 结果是家长端一直停在"连接中"，且平板上看不到任何提示，排查时极难定位。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * 当前仍未拿到的运行时权限。
     *
     * UI 用它决定是否需要向系统申请：已全部就位时**绝不重复弹系统窗**，
     * 重复申请会让家长以为"刚才那次白点了"。
     */
    fun missingRuntimePermissions(): List<String> =
        requiredPermissions.filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

    data class GrantReport(
        val granted: List<String> = emptyList(),
        val failed: List<String> = emptyList(),
        val unsupported: List<String> = emptyList(),
        /** 需用户在系统设置页手动打开的项（AppOps / 无障碍，见 [SpecialPermission]） */
        val needManual: List<SpecialPermission> = emptyList(),
        val hardening: List<Pair<String, OpResult>> = emptyList()
    ) {
        val allOk: Boolean get() = failed.isEmpty()
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
     * 一次性拿走所有能由 App 自己搞定的东西：运行时权限 + 加固基线 + 协议留痕，
     * 并如实回报哪些项还得家长去系统页手动开。
     *
     * - 已是 DO/PO：全部静默完成，[needManual] 只剩系统设置项；
     * - 非 DO/PO：运行时权限返回 [OpResult.Unsupported]，由上层改用系统弹窗。
     */
    suspend fun grantAll(
        version: String = AuthRepository.CURRENT_AGREEMENT_VERSION
    ): GrantReport = withContext(Dispatchers.Default) {
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

        // AppOps 特殊权限与无障碍服务**连设备所有者都无权代授**，
        // 这里只能如实检测并标记，由上层把家长送到对应系统设置页。
        // 放在运行时权限之后统一处理，才能让它们真正出现在"一次确认"的清单里，
        // 而不是像以前那样只漏使用情况访问一项 —— 漏的代价是水印不显示、
        // 密码闸门被孩子从设置页绕过。
        val needManual = missingSpecialPermissions()

        val hardening = admin.applyHardeningBaseline()

        // 协议持久化（用户已明确同意 → 无论授予结果如何都照常留痕）
        persistAgreement(version)

        Logger.i(TAG) {
            "grantAll: ok=${granted.size}, unsupported=${unsupported.size}, " +
                "failed=${failed.size}, needManual=${needManual.map { it.key }}, " +
                "hardeningApplied=${hardening.size}"
        }
        GrantReport(granted, failed, unsupported, needManual, hardening)
    }

    /**
     * 当前仍缺的 AppOps / 无障碍项（不含 INSTALL_PACKAGES 在 DO 下的冗余项）。
     *
     * 每次从系统设置页返回前台都必须重新查一次：这类开关的变更**没有任何回调通知**，
     * 不复检就只能让家长再点一次按钮，做不到"一回来就自动往下走"。
     */
    fun missingSpecialPermissions(): List<SpecialPermission> =
        SpecialPermission.missing(context, admin.hasOwnerPrivileges())

    /**
     * 开机/服务重启后的自愈补授：只把**当前缺失**的运行时权限重新置为已授予。
     *
     * 与 [grantAll] 的区别：不写协议、不做加固基线（那些是一次性的，重复执行没意义还拖慢启动），
     * 因此可以被守护服务在每次 bootstrap 时无副作用地调用。
     *
     * 为什么必须有这一步：DO 的静默授予只在用户点「同意并授权」那一次执行过。
     * 之后只要重装应用（覆盖安装会重置运行时权限）或系统清掉授予状态，
     * 权限就永久丢失 —— 表现最明显的就是通知权限缺失：
     * 屏幕采集的引导通知根本收不到，家长端一直转圈"连接中"，
     * 而平板上没有任何提示，从现象完全看不出是权限问题。
     */
    suspend fun ensureGranted(): Int = withContext(Dispatchers.Default) {
        val missing = missingRuntimePermissions()
        if (missing.isEmpty()) return@withContext 0
        var ok = 0
        for (perm in missing) {
            when (admin.grantRuntimePermission(perm)) {
                is OpResult.Ok -> ok++
                is OpResult.Failed -> Logger.w(TAG) { "re-grant failed: $perm" }
                is OpResult.Unsupported -> Unit
            }
        }

        // AppOps / 无障碍**无法自愈** —— 任何第三方 App 都无权代为打开，
        // 所以这里不是"补授"而只是如实打点：
        // 装机时开过、之后被家长在系统里关掉或被 ROM 清掉时，
        // 应用限额、水印与密码闸门会**再次静默失效**（现象上完全看不出是权限问题）。
        // 不打这条日志，这类回归就彻底无从追查。
        val missingSpecial = missingSpecialPermissions()
        if (missingSpecial.isNotEmpty()) {
            Logger.w(TAG) {
                "需用户在系统设置重开: ${missingSpecial.map { it.key }}"
            }
        }

        Logger.i(TAG) { "ensureGranted: missing=${missing.size}, reGranted=$ok" }
        ok
    }

    /**
     * 持久化监护协议（用户已明确「同意并授权」后的合规留痕）。
     *
     * 抽成独立方法，供两类授权入口共用，确保"同意"在任意路径下只落一次、且必定留痕：
     * - DO/PO 路径：[grantAll] 内部已调用；
     * - 非 DO 路径：[com.padguard.child.consent.OneTimeConsentViewModel] 在系统运行时权限
     *   弹窗结果回来后调用。
     *
     * 与具体权限是否授予成功解耦：用户已点击"同意并授权"即视为明确同意，
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
