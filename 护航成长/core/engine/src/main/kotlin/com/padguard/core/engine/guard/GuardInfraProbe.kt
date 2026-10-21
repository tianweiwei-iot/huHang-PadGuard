package com.padguard.core.engine.guard

/**
 * 守护基建状态探针（S12 / N2 接口下沉）。
 *
 * ## 为什么是接口而不是直接写检测逻辑
 * "我方无障碍服务是否启用""使用情况访问权限是否还在"这两项检测**实现上依赖 app 层**：
 * - 无障碍：必须拿得到本 App 的 Service 组件名（`com.padguard.child.guard.*`），
 *   core/engine 无法（也不应）反向依赖 app 层；
 * - Usage Access：AppOpsManager 的查询本身可用 Context 做，但与无障碍同属
 *   "守护基建是否还在"的语义，两处检查口径应收敛在一处。
 *
 * 因此接口定义在 core/engine（被 [TamperDetector] 消费），实现放 app 层经 Hilt 绑定注入 ——
 * 依赖方向保持 core ← app，不出现反向依赖，也让 JVM 单元测试可以用假探针驱动 TamperDetector。
 */
interface GuardInfraProbe {

    /** 我方的无障碍服务当前是否处于启用状态（设置开关是否打开） */
    fun isAccessibilityServiceEnabled(): Boolean

    /**
     * 我方的无障碍服务是否**真正在运行**。
     *
     * 与 [isAccessibilityServiceEnabled] 的区别：后者只查「系统设置里开关是否打开」，
     * 开关开着、但服务进程被系统/省电策略杀死时它仍返回 true。本方法额外结合服务自身
     * 上报的「最近存活心跳」，区分「用户手动关闭」与「进程被系统杀掉」两种本质不同的失效，
     * 避免把一次可自愈的进程死亡误报成「操作闸门被人为关闭」。
     */
    fun isAccessibilityServiceAlive(): Boolean

    /** 使用情况访问权限（Usage Access）当前是否已授予 */
    fun isUsageAccessGranted(): Boolean
}
