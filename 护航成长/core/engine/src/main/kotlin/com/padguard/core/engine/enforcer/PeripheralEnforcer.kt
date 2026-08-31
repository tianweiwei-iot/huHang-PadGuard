package com.padguard.core.engine.enforcer

import android.os.Build
import android.os.UserManager
import com.padguard.core.common.Logger
import com.padguard.core.data.model.policy.PeripheralPolicy
import com.padguard.core.data.model.policy.PolicySwitch
import com.padguard.core.engine.admin.Capability
import com.padguard.core.engine.admin.DeviceAdminBridge
import com.padguard.core.engine.admin.OpResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 外设与硬件能力管控。
 *
 * ## 语义约定（[PolicySwitch] 到底怎么落地）
 * | 取值 | 含义 | 落地手段 |
 * |---|---|---|
 * | ALLOW | 不干预，用户可自由开关 | 清除对应用户限制 |
 * | DISABLE | 禁用且用户不能开启 | 加用户限制（+ 必要时先关掉当前状态） |
 * | FORCE_ON | 强制开启且不能关闭 | 加"禁止修改配置"限制并确保当前是开启态 |
 * | FORCE_OFF | 等价于 DISABLE | 同 DISABLE |
 * | LOCKED | 锁定在当前状态（不改变开关，只禁止修改） | 只加"禁止修改配置"限制 |
 *
 * ## 关键取舍
 * 1. **不去直接改开关状态**（如强行关 WiFi）。Android 10 起 `WifiManager.setWifiEnabled`
 *    对第三方应用已失效，DO 也只能通过用户限制阻止"修改"，不能可靠地"关闭"。
 *    因此 DISABLE 的真实语义是"用户改不了"，而不是"一定处于关闭态"。
 *    这一点必须写进契约，否则管控端的 UI 会给出错误承诺。
 * 2. **麦克风只能靠 DISALLOW_UNMUTE_MICROPHONE**，即"锁定在静音"。
 *    真正的"禁用麦克风"在 Android 上没有 DO 级 API，只有厂商私有方案。
 * 3. **录屏无法与截屏分开**：`setScreenCaptureDisabled` 同时封掉两者。
 *    因此两个字段任一为禁用即整体禁用，并在报告里标注语义合并，避免管控端误解。
 */
@Singleton
class PeripheralEnforcer @Inject constructor(
    private val admin: DeviceAdminBridge
) {

    fun apply(policy: PeripheralPolicy): EnforceReport {
        val report = EnforceReport()

        // 策略重刷即临时覆盖失效：避免出现"策略说禁、设备说放"的双源真相
        clearOverrides()

        if (!admin.can(Capability.USER_RESTRICTIONS)) {
            report.markUnsupported(
                "peripheral.*",
                "当前为 ${admin.controlMode.value}，无法下发用户限制，外设管控整体不可用"
            )
            // 即使没有 DO/PO，相机在 DEVICE_ADMIN 模式下仍可禁用，单独尝试
            applyCamera(policy.camera, report)
            return report
        }

        // ---------- 网络类 ----------
        restrict("peripheral.wifi", UserManager.DISALLOW_CONFIG_WIFI, policy.wifi, report)
        restrict("peripheral.bluetooth", UserManager.DISALLOW_BLUETOOTH, policy.bluetooth, report)
        restrict(
            "peripheral.mobileData",
            UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS,
            policy.mobileData,
            report
        )
        restrict("peripheral.hotspot", UserManager.DISALLOW_CONFIG_TETHERING, policy.hotspot, report)

        // ---------- 定位 ----------
        // GPS 的 FORCE_ON 语义特殊：需要"禁止用户关定位"而不是"禁止配置定位"
        when (policy.gps) {
            PolicySwitch.FORCE_ON, PolicySwitch.LOCKED ->
                report.record(
                    "peripheral.gps=FORCE_ON",
                    admin.addUserRestriction(UserManager.DISALLOW_CONFIG_LOCATION)
                )

            PolicySwitch.DISABLE, PolicySwitch.FORCE_OFF -> {
                report.record(
                    "peripheral.gps=DISABLE",
                    admin.addUserRestriction(UserManager.DISALLOW_SHARE_LOCATION)
                )
                report.record(
                    "peripheral.gps.lockConfig",
                    admin.addUserRestriction(UserManager.DISALLOW_CONFIG_LOCATION)
                )
            }

            PolicySwitch.ALLOW -> {
                report.record("peripheral.gps=ALLOW", admin.clearUserRestriction(UserManager.DISALLOW_SHARE_LOCATION))
                admin.clearUserRestriction(UserManager.DISALLOW_CONFIG_LOCATION)
            }
        }

        // ---------- 采集类 ----------
        applyCamera(policy.camera, report)
        applyMicrophone(policy.microphone, report)
        applyScreenCapture(policy.screenCapture, policy.screenRecord, policy.castScreen, report)

        // ---------- 物理接口 ----------
        restrict(
            "peripheral.usbFileTransfer",
            UserManager.DISALLOW_USB_FILE_TRANSFER,
            policy.usbFileTransfer,
            report
        )
        if (policy.usbFileTransfer.isBlocking()) {
            // 顺带禁挂载物理介质，否则插 SD 卡仍能倒数据
            report.record(
                "peripheral.mountMedia",
                admin.addUserRestriction(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA)
            )
        }
        restrict("peripheral.nfc", UserManager.DISALLOW_OUTGOING_BEAM, policy.nfc, report)

        Logger.i(TAG) { "peripheral policy applied: $report" }
        return report
    }

    private fun applyCamera(value: PolicySwitch, report: EnforceReport) {
        if (!admin.can(Capability.DISABLE_CAMERA)) {
            report.markUnsupported("peripheral.camera", "需要激活设备管理器")
            return
        }
        val disabled = value.isBlocking()
        report.record("peripheral.camera=$value", admin.setCameraDisabled(disabled))
        // setCameraDisabled 只封 Camera API；Android 12+ 还需锁住快捷开关，否则用户能一键放行
        if (admin.can(Capability.USER_RESTRICTIONS) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            admin.setUserRestriction(UserManager.DISALLOW_CAMERA_TOGGLE, disabled)
        }
    }

    private fun applyMicrophone(value: PolicySwitch, report: EnforceReport) {
        val blocking = value.isBlocking()
        report.record(
            "peripheral.microphone=$value",
            admin.setUserRestriction(UserManager.DISALLOW_UNMUTE_MICROPHONE, blocking)
        )
        if (blocking && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            admin.setUserRestriction(UserManager.DISALLOW_MICROPHONE_TOGGLE, true)
        }
    }

    /**
     * 截屏 / 录屏 / 投屏。
     *
     * 三者在 Android 上共用同一个开关（[DeviceAdminBridge.setScreenCaptureDisabled]），
     * 任一要求禁用即整体禁用。报告里显式写出合并语义，避免管控端以为能分开控制。
     */
    private fun applyScreenCapture(
        capture: PolicySwitch,
        record: PolicySwitch,
        cast: PolicySwitch,
        report: EnforceReport
    ) {
        val shouldDisable = capture.isBlocking() || record.isBlocking() || cast.isBlocking()
        if (!admin.can(Capability.DISABLE_SCREEN_CAPTURE)) {
            report.markUnsupported("peripheral.screenCapture", "需要 Device Owner / Profile Owner")
            return
        }
        report.record(
            "peripheral.screenCapture(合并截屏/录屏/投屏)=$shouldDisable",
            admin.setScreenCaptureDisabled(shouldDisable)
        )
        if (capture.isBlocking() != record.isBlocking() || capture.isBlocking() != cast.isBlocking()) {
            report.markUnsupported(
                "peripheral.screenCapture.granularity",
                "系统不支持分别控制截屏/录屏/投屏，已按最严格项统一处理"
            )
        }
    }

    /** 通用限制映射：blocking → 加限制，ALLOW → 清限制，LOCKED → 只锁配置 */
    private fun restrict(name: String, restrictionKey: String, value: PolicySwitch, report: EnforceReport) {
        val result = when (value) {
            PolicySwitch.ALLOW -> admin.clearUserRestriction(restrictionKey)
            PolicySwitch.DISABLE, PolicySwitch.FORCE_OFF, PolicySwitch.LOCKED, PolicySwitch.FORCE_ON ->
                admin.addUserRestriction(restrictionKey)
        }
        report.record("$name=$value", result)
    }

    // ================== 远程临时覆盖（PERIPHERAL_OVERRIDE 指令） ==================

    /**
     * 单项外设的远程临时放行 / 回收。
     *
     * ## 生命周期（必须让管控端知道）
     * 这是**临时覆盖**，只改当前系统状态，不写入策略：
     * - 下一次 [apply]（策略下发或周期自检重刷）会按策略值覆盖掉本次覆盖；
     * - 设备重启后用户限制本身仍在（系统持久化），但我们无法区分"策略要求"与"临时放行"，
     *   因此重启后一律由 [apply] 重新对齐到策略值。
     *
     * 之所以不做成"持久化覆盖"，是因为一旦持久化就会出现"策略说禁、设备说放"的双源真相，
     * 后续排障根本说不清是谁生效。宁可让临时放行有明确的失效时机。
     *
     * @param item 外设项名，兼容驼峰 / 下划线 / 大小写（wifi、mobileData、mobile_data 均可）
     * @param enabled true = 放行（清限制），false = 禁用（加限制）
     */
    fun override(item: String, enabled: Boolean): OpResult {
        val normalized = normalizeItem(item)
        val op = "peripheralOverride:$normalized=$enabled"

        // 相机走独立 API，DEVICE_ADMIN 模式下也可用，先处理再判 USER_RESTRICTIONS
        if (normalized == ITEM_CAMERA) return overrideCamera(enabled, op)
        if (normalized == ITEM_SCREEN_CAPTURE) return overrideScreenCapture(enabled, op)

        if (!admin.can(Capability.USER_RESTRICTIONS)) {
            return OpResult.Unsupported(op, "当前为 ${admin.controlMode.value}，无法下发用户限制")
        }

        val result = when (normalized) {
            ITEM_WIFI -> switchRestriction(UserManager.DISALLOW_CONFIG_WIFI, enabled)
            ITEM_BLUETOOTH -> switchRestriction(UserManager.DISALLOW_BLUETOOTH, enabled)
            ITEM_MOBILE_DATA -> switchRestriction(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS, enabled)
            ITEM_HOTSPOT -> switchRestriction(UserManager.DISALLOW_CONFIG_TETHERING, enabled)
            ITEM_GPS -> overrideGps(enabled)
            ITEM_MICROPHONE -> overrideMicrophone(enabled)
            ITEM_USB -> overrideUsb(enabled)
            ITEM_NFC -> switchRestriction(UserManager.DISALLOW_OUTGOING_BEAM, enabled)
            else -> OpResult.Unsupported(op, "未知外设项：$item，支持范围 $SUPPORTED_ITEMS")
        }

        if (result.isOk) {
            _overrides[normalized] = enabled
            Logger.i(TAG) { "peripheral override applied: $normalized=$enabled（下次策略下发将覆盖）" }
        }
        return result
    }

    /** 当前生效的临时覆盖快照，供状态页 / 心跳上报展示"为什么和策略不一致" */
    fun activeOverrides(): Map<String, Boolean> = _overrides.toMap()

    /** 策略重新下发时调用：临时覆盖失效 */
    fun clearOverrides() {
        if (_overrides.isNotEmpty()) {
            Logger.i(TAG) { "peripheral overrides cleared: ${_overrides.keys}" }
            _overrides.clear()
        }
    }

    private fun overrideCamera(enabled: Boolean, op: String): OpResult {
        if (!admin.can(Capability.DISABLE_CAMERA)) {
            return OpResult.Unsupported(op, "需要激活设备管理器")
        }
        val result = admin.setCameraDisabled(!enabled)
        if (result.isOk) {
            if (admin.can(Capability.USER_RESTRICTIONS) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                admin.setUserRestriction(UserManager.DISALLOW_CAMERA_TOGGLE, !enabled)
            }
            _overrides[ITEM_CAMERA] = enabled
        }
        return result
    }

    private fun overrideScreenCapture(enabled: Boolean, op: String): OpResult {
        if (!admin.can(Capability.DISABLE_SCREEN_CAPTURE)) {
            return OpResult.Unsupported(op, "需要 Device Owner / Profile Owner")
        }
        val result = admin.setScreenCaptureDisabled(!enabled)
        if (result.isOk) _overrides[ITEM_SCREEN_CAPTURE] = enabled
        return result
    }

    /**
     * GPS 放行 = 同时清掉"禁止共享位置"和"禁止配置定位"两条限制。
     * 只清一条会出现"能开定位但改不了模式"这种半死状态，用户会以为是 Bug。
     */
    private fun overrideGps(enabled: Boolean): OpResult {
        val primary = admin.setUserRestriction(UserManager.DISALLOW_SHARE_LOCATION, !enabled)
        admin.setUserRestriction(UserManager.DISALLOW_CONFIG_LOCATION, !enabled)
        return primary
    }

    /** 麦克风只能"锁定静音"，放行即解除静音锁；见类注释关键取舍 2 */
    private fun overrideMicrophone(enabled: Boolean): OpResult {
        val primary = admin.setUserRestriction(UserManager.DISALLOW_UNMUTE_MICROPHONE, !enabled)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            admin.setUserRestriction(UserManager.DISALLOW_MICROPHONE_TOGGLE, !enabled)
        }
        return primary
    }

    /** USB 与物理介质挂载成对处理，否则放行 USB 后插 SD 卡仍被拦，现场很难解释 */
    private fun overrideUsb(enabled: Boolean): OpResult {
        val primary = admin.setUserRestriction(UserManager.DISALLOW_USB_FILE_TRANSFER, !enabled)
        admin.setUserRestriction(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA, !enabled)
        return primary
    }

    private fun switchRestriction(key: String, enabled: Boolean): OpResult =
        admin.setUserRestriction(key, !enabled)

    /**
     * 归一化：去下划线/连字符 + 转小写 + 别名折叠，容忍服务端字段命名风格漂移。
     *
     * 别名折叠是必需的，不是"顺手做的兼容"：
     * screenRecord / castScreen 在系统层与 screenCapture 是同一个开关，
     * 若不折叠，管控端下发"放行录屏"会得到 UNSUPPORTED，运维会当成 Bug 反复上报。
     */
    private fun normalizeItem(item: String): String {
        val flat = item.trim().lowercase().replace("_", "").replace("-", "")
        return ALIASES[flat] ?: flat
    }

    companion object {
        private const val TAG = "PeripheralEnforcer"

        const val ITEM_WIFI = "wifi"
        const val ITEM_BLUETOOTH = "bluetooth"
        const val ITEM_MOBILE_DATA = "mobiledata"
        const val ITEM_HOTSPOT = "hotspot"
        const val ITEM_GPS = "gps"
        const val ITEM_CAMERA = "camera"
        const val ITEM_MICROPHONE = "microphone"
        const val ITEM_SCREEN_CAPTURE = "screencapture"
        const val ITEM_USB = "usbfiletransfer"
        const val ITEM_NFC = "nfc"

        private val SUPPORTED_ITEMS = listOf(
            ITEM_WIFI, ITEM_BLUETOOTH, ITEM_MOBILE_DATA, ITEM_HOTSPOT, ITEM_GPS,
            ITEM_CAMERA, ITEM_MICROPHONE, ITEM_SCREEN_CAPTURE, ITEM_USB, ITEM_NFC
        )

        /** 别名 → 标准项。左侧已是去下划线小写形态 */
        private val ALIASES = mapOf(
            "screenrecord" to ITEM_SCREEN_CAPTURE,
            "castscreen" to ITEM_SCREEN_CAPTURE,
            "screenshot" to ITEM_SCREEN_CAPTURE,
            "usb" to ITEM_USB,
            "mobile" to ITEM_MOBILE_DATA,
            "cellular" to ITEM_MOBILE_DATA,
            "tethering" to ITEM_HOTSPOT,
            "location" to ITEM_GPS,
            "mic" to ITEM_MICROPHONE
        )
    }

    private val _overrides = mutableMapOf<String, Boolean>()
}

/**
 * 是否属于"限制用户操作"的取值。
 *
 * FORCE_ON 也算限制：它的落地手段同样是"禁止用户修改配置"，
 * 区别只在于期望的当前状态不同（由各自的分支单独处理）。
 */
internal fun PolicySwitch.isBlocking(): Boolean = when (this) {
    PolicySwitch.DISABLE, PolicySwitch.FORCE_OFF -> true
    PolicySwitch.ALLOW, PolicySwitch.FORCE_ON, PolicySwitch.LOCKED -> false
}
