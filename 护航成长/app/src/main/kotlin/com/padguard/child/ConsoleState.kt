package com.padguard.child

/**
 * 当前是否处于「家长操作态」的全局标记。
 *
 * ## 为什么需要它
 * 看屏授权（MediaProjection）要求孩子/家长在系统弹窗里点一次"立即开始"，
 * 这是 Android 的隐私红线，应用无法静默代确认。历史实现里，家长端一下发看屏/录屏指令、
 * 被控端恰好没有令牌时，[com.padguard.child.capture.CaptureConsentPrompter] 会**直接拉起
 * 授权页让孩子点**，或发高优先级通知让孩子点 —— 等于"使用过程中指望孩子确认授权"。
 *
 * 加固目标：授权必须在**装机**或家长经「补授权」入口**主动**完成时取得，孩子正常使用设备时
 * 绝不向孩子弹确认框。判定"此刻是谁在拿设备"的唯一可靠信号是：当前是否处于家长控制台 /
 * 授权引导在场（揭示会话 / 一次性授权流程）。本标记由 [MainActivity] 的入口分流在对应分支写入。
 *
 * ## 约定
 * - 家长控制台（reveal 会话）或一次性授权流程在场 → true（家长在操作，允许弹确认框）；
 * - 隐藏态（孩子正常使用平板） → false（绝不打扰孩子，看屏指令静默失败，由家长去补授权入口重授权）。
 *
 * 用 `@Volatile` 保证多线程（守护服务下发指令线程 ↔ 主线程写入）可见性；它只是一个布尔标记，
 * 不持有任何 Activity / Context 引用，避免内存泄漏。
 */
object ConsoleState {

    @Volatile
    var isManagementVisible: Boolean = false
        private set

    fun setManagementVisible(visible: Boolean) {
        isManagementVisible = visible
    }
}
