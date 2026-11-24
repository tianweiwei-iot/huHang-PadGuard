package com.padguard.core.data.model.policy

/**
 * 家长在装机首启时选择的**管控模式**（用户意图），与 [ControlMode]（设备实际权限等级）刻意解耦。
 *
 * ## 为什么要独立一个枚举
 * [ControlMode] 由 DeviceAdminBridge 运行时用 dpm 探测得出，回答"设备实际是什么"；
 * 本枚举回答"家长想用什么"。二者必须分开：
 * - 设备已是 DO，但家长选了软管控 → 不启用硬管控专属的 Kiosk 真锁死与权限强制回收；
 * - 设备还不是 DO，但家长选了硬管控 → 进入 DO 预置引导，不悄悄降级；
 * - 两档的授权与策略配置各自保留，切换不清除另一档数据（"互不影响"）。
 *
 * ## 与 Android 系统边界的关系
 * 硬管控能力依赖设备所有者(DO)，而 App 无法自行提权为 DO（只能外部 adb / DPC 二维码预置）。
 * 因此选择硬管控后仍需按引导完成预置；未完成时不会偷偷放行，也不会把用户的选择丢掉。
 */
enum class ManagementMode(val storageValue: String, val label: String) {
    HARD("HARD", "硬管控"),
    SOFT("SOFT", "软管控");

    companion object {
        /** DataStore 存字符串；未选择或读到脏值时返回 null，由入口分流引导用户重新选择。 */
        fun fromStorage(value: String?): ManagementMode? =
            values().firstOrNull { it.storageValue == value }
    }
}
