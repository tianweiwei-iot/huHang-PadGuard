package com.padguard.core.engine.admin

/**
 * [DeviceAdminBridge] 的可测性抽象（端口）。
 *
 * ## 为什么需要这一层
 * [com.padguard.core.engine.command.CommandExecutor] 只依赖这个**窄接口**而非具体实现
 * [DeviceAdminBridge]。后者是引用了大量 Android 框架类型（`DevicePolicyManager` / `Context`
 * / `UserManager` …）的 `final` 类，在纯 JVM 单测环境下无法被 Mockito inline 代理
 * （Mockito 无法 instrument 引用了 Android 类型的 final 类，只有 AGP 专用测试 JVM 能克服）。
 *
 * 抽接口后，单测可以 mock 一个**不引用任何 Android 类型**的端口
 * （[OpResult] / [SuspendResult] / [Capability] 均为纯 Kotlin 类型），从而脱离 Android
 * 运行时、用任意 JUnit harness 跑通 —— 这正是本项目"手写 standalone Runner 在沙箱里真跑单测"
 * 能成立的前提。
 *
 * ## 设计边界
 * 这里只暴露 [CommandExecutor] 实际调用的方法/属性，保持最小面；其余 8 个消费者
 * （各 Enforcer / TamperDetector / PolicyEngine / SystemLockEnforcer …）仍直接依赖具体类
 * [DeviceAdminBridge]，不在本次改动范围内。接口与实现同包，复用同文件定义的纯类型。
 */
interface DeviceAdminBridgePort {

    /** 能力查询：当前权限模式下能否执行某类 DPM 操作 */
    fun can(capability: Capability): Boolean

    /** 本应用自身的包名：用于拒绝"把自己隐藏掉"这类自杀式指令 */
    val selfPackageName: String

    /** 应用当前是否被挂起（写进系统的持久状态） */
    fun isPackageSuspended(packageName: String): Boolean

    /** 挂起 / 解挂应用：图标变灰、点击弹系统提示，但应用数据保留 */
    fun setPackagesSuspended(packages: List<String>, suspended: Boolean): SuspendResult

    /** 重启（仅 Device Owner） */
    fun reboot(): OpResult

    /**
     * 记录"家长关闭 / 开启某应用使用权限"的**意图**，与设备是否 Device Owner 无关。
     * 这是非 DO 设备上"密码门禁"软拦截的唯一事实来源。
     */
    fun persistHidden(packageName: String, hidden: Boolean)

    /**
     * 系统级隐藏 / 取消隐藏应用（图标消失、无法启动）。
     * DO/PO 路径下用于"取消授权改为密码门禁"时确保应用可见。
     */
    fun setApplicationHidden(packageName: String, hidden: Boolean): OpResult

    /** 经 DO/PO 特权设置某包名的运行时权限授予状态（授予 / 回归默认） */
    fun setRuntimePermission(packageName: String, permission: String, grant: Boolean): OpResult

    /**
     * 功能管理：把孩子端收到的"功能开关"整包应用为 DPM 限制 / 系统开关。
     * 逐项返回成功/失败，便于上层汇总成"哪些功能未生效"。
     */
    fun applyFeaturePolicy(features: Map<String, Boolean>): List<Pair<String, OpResult>>

    /** 恢复出厂（高危，仅 Device Owner） */
    fun wipeData(): OpResult
}
