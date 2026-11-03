package com.padguard.child.guard

/**
 * 「卸载需要家长密码」到底有没有真正生效。
 *
 * ## 为什么需要一个显式状态
 * 2026-11-02 现场事故：孩子在桌面上**长按「护航成长」图标 → 卸载 → 直接卸载成功**，
 * 整个管控当场失效，全程没有出现过一次密码框。事后从设备日志里能看出的只有两行事实：
 * `PolicyEngine policy applied: security.antiUninstall 需要 Device Owner`
 * 与**整段日志里一条 `GuardA11y` 都没有**（无障碍服务从未连接过）。
 *
 * 也就是说：产品对外承诺的"卸载需密码"其实由三条互不相同的通道承担，而当时**三条全空**，
 * 系统里没有任何一层会拦住卸载 —— 但界面上没有任何一处告诉家长"你的防护现在是 0"。
 * 这是比"某条通道坏了"更严重的缺陷：**失效不可见**。本枚举把这件事变成可计算、可上报的状态。
 *
 * ## 四层防御与它们各自的能力边界（务必如实，不要对外夸大）
 * | 状态 | 依赖 | 实际效果 |
 * |---|---|---|
 * | [DO_BLOCKED] | Device Owner / Profile Owner | 系统级封死：连"卸载"按钮都点不动（`setUninstallBlocked`） |
 * | [ADMIN_SHIELD] | 设备管理员激活 | 系统要求**先停用管理员才能卸载**，而停用页被闸门挡 + 警告文案 |
 * | [SOFT_GATE] | 无障碍服务 + 已设家长密码 | 卸载确认页 / 桌面卸载入口被顶回并弹密码框（软门） |
 * | [SOFT_GATE_WITHOUT_PASSWORD] | 只有无障碍服务 | 只能顶回，无法验身——孩子可以反复试，属"半残" |
 * | [NONE] | 什么都没有 | **长按图标即可卸载**，与事故现场一致 |
 */
enum class UninstallShield(
    /** 数值越大越强；用于"只降不升"的判断与日志可读性。 */
    val strength: Int,
    /** 面向家长的一句话说明（白话，不出现 API 名）。 */
    val parentSummary: String
) {
    NONE(0, "当前无任何卸载防护：孩子长按桌面图标即可卸载本应用"),
    SOFT_GATE_WITHOUT_PASSWORD(1, "卸载入口会被弹回，但尚未设置操作密码，无法验证家长身份"),
    SOFT_GATE(2, "卸载时会要求输入家长密码（无障碍拦截）"),
    ADMIN_SHIELD(3, "卸载前必须先停用设备管理员，否则系统拒绝卸载"),
    DO_BLOCKED(4, "设备所有者已系统级封死卸载");

    /** 是否至少有"一层"能拦住孩子随手卸载；[SOFT_GATE_WITHOUT_PASSWORD] 只算半层，按不可拦截处理。 */
    val blocksCasualUninstall: Boolean
        get() = this == DO_BLOCKED || this == ADMIN_SHIELD || this == SOFT_GATE

    /** 与事故直接相关的告警口径：只有这四种里最低的两种需要打扰家长。 */
    val needsParentAttention: Boolean
        get() = !blocksCasualUninstall
}

/**
 * [UninstallShield] 的判定器（纯函数，便于单测）。
 *
 * 判定顺序刻意"从强到弱"：先看有没有系统级封死，再看设备管理员，最后才看软门。
 * 这样只要存在更强的一层，就不会因为"软门没开"而误报成无防护——
 * 误报会让家长对告警脱敏，等于把真正的失效也一起埋掉。
 */
object UninstallShieldResolver {

    /**
     * @param ownerPrivileges 是否持有 DO/PO（只有它们能调用 `setUninstallBlocked`）
     * @param uninstallBlockApplied 本机是否已实际把"禁止卸载自身"落到系统（[UninstallShield.DO_BLOCKED] 的必要条件）
     * @param adminActive 设备管理员是否处于激活态（系统会要求先停用才能卸载）
     * @param a11yAlive 操作闸门无障碍服务是否**真正在运行**（不是"开关打开"而是有心跳）
     * @param passwordEnabled 是否已设置家长操作密码（软门能否验身的前提）
     */
    fun resolve(
        ownerPrivileges: Boolean,
        uninstallBlockApplied: Boolean,
        adminActive: Boolean,
        a11yAlive: Boolean,
        passwordEnabled: Boolean
    ): UninstallShield = when {
        // ① 系统级封死：连卸载入口都不会出现"确认"以外的结果
        ownerPrivileges && uninstallBlockApplied -> UninstallShield.DO_BLOCKED
        // ② 设备管理员：系统要求先停用管理员，停用页由闸门 + onDisableRequested 拦
        adminActive -> UninstallShield.ADMIN_SHIELD
        // ③ 软门：无障碍在位才谈得上"弹密码框"
        a11yAlive && passwordEnabled -> UninstallShield.SOFT_GATE
        a11yAlive -> UninstallShield.SOFT_GATE_WITHOUT_PASSWORD
        else -> UninstallShield.NONE
    }
}
