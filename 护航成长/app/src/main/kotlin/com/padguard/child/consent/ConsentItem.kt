package com.padguard.child.consent

import android.content.Intent

/**
 * 一次性授权清单里的一行。
 *
 * ## 为什么要把差别的授权项建模成同一种东西
 * Android 上"取得一项能力"有四种完全不同的方式：
 * - 运行时权限：系统弹窗允许即可（DO 机型还能静默授予）；
 * - 设备管理员：系统确认页点「激活」；
 * - AppOps / 无障碍：只能进系统设置页手动拨开关；
 * - 屏幕采集：MediaProjection 系统弹窗点「立即开始」。
 *
 * 如果 UI 按类别各写一套分支（历史上正是如此），新增一项就得改三处，
 * 而且"还差几项"这种最基础的问题都答不上来。现在统一成一行的不同状态，
 * 清单渲染、进度统计、推进判定都只认这一种模型。
 */
data class ConsentItem(
    val key: String,
    val title: String,
    /** 缺失后果（家长看得懂的白话，不写技术名词） */
    val impact: String,
    val source: Source,
    val state: State = State.PENDING
) {
    /** 取得方式，决定由谁往下推进。 */
    enum class Source {
        /** 运行时权限：DO 静默授予，或由系统弹窗一次性申请 */
        RUNTIME,
        /** 设备管理员身份：系统确认页 */
        DEVICE_ADMIN,
        /** AppOps / 无障碍：必须进系统设置页手动打开 */
        MANUAL_SETTING,
        /** 屏幕采集：MediaProjection 系统弹窗 */
        MEDIA_PROJECTION
    }

    enum class State {
        /** 待处理 */
        PENDING,
        /** 正在处理（App 正在后台静默办理） */
        RUNNING,
        /** 已到位 */
        DONE,
        /** 需要家长在系统设置页手动操作 */
        NEED_MANUAL,
        /** 被家长明确拒绝 / 本机不支持 */
        SKIPPED
    }
}

/**
 * 需要页面代为发起的动作（ViewModel 不持有 Activity，一律以事件外抛）。
 *
 * 统一成密封类而不是三个独立的 SharedFlow，是为了让页面只有一处分发逻辑，
 * 不会出现"某个 Flow 忘了收集，家长点了却没反应"。
 */
sealed interface ConsentLaunch {

    /** 一次性向系统申请多个运行时权限 */
    data class Runtime(val permissions: List<String>) : ConsentLaunch

    /** 打开设备管理员激活确认页 */
    data class DeviceAdmin(val intent: Intent) : ConsentLaunch

    /** 打开某个 AppOps / 无障碍项的系统设置页 */
    data class Settings(val key: String, val intent: Intent) : ConsentLaunch

    /** 申请屏幕采集（MediaProjection 系统弹窗） */
    data object MediaProjection : ConsentLaunch
}
