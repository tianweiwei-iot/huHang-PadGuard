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
    val state: State = State.PENDING,
    /**
     * 是否属于「可选增强」。
     *
     * 目前只有设备管理员是 true：没有它在多数机型上仍能正常管控（锁屏靠无障碍 + 前台服务，
     * 应用管控靠门禁），只是少了防卸载 / 禁相机这类加固。UI 据此把文案从
     * "未完成"改成"可选，可稍后补开"——把"没做完"和"做不到"区分开，
     * 家长才不会以为流程卡住了。
     */
    val optional: Boolean = false
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
        MEDIA_PROJECTION,
        /**
         * VPN 授权：系统弹出的「允许此应用设置 VPN 连接」确认框。
         *
         * 与 MEDIA_PROJECTION 同属"一次性系统确认"，但发起 Intent 来自
         * `VpnService.prepare()`，结果也走 ActivityResult，故单列一类：
         * 混进 MANUAL_SETTING 会被当成"去设置页拨开关"，永远等不到那个框。
         */
        VPN
    }

    enum class State {
        /** 待处理 */
        PENDING,
        /** 正在处理（App 正在后台静默办理） */
        RUNNING,
        /** 已到位（查询 API 确认已授予） */
        DONE,
        /** 需要家长在系统设置页手动操作 */
        NEED_MANUAL,
        /**
         * 已把家长带到设置页，但该开关**没有公开查询接口**（厂商自启动 / 后台弹出界面）。
         *
         * 与 DONE 的区别是诚实：Android 不提供这类开关的状态，App 无权断言"已开启"。
         * 与 NEED_MANUAL 的区别是流程：它不再计入"剩余待办"，流水线会自动接续下一项，
         * 家长不必回来再点一次「继续」。行上仍保留「去开启」入口，随时可重开该页确认。
         */
        CONFIRMED,
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

    /**
     * 申请 VPN 授权（`VpnService.prepare()` 返回的确认框 Intent）。
     *
     * 必须是"由系统生成的 Intent 原样交给页面去 startActivityForResult"，
     * App 无法伪造也无法跳过 —— 这是 Android 对全设备流量通道的硬性门槛。
     * 设备所有者机型不走这里（控制器会先尝试 always-on 代授权，成功即无需弹窗）。
     */
    data class VpnPrepare(val intent: Intent) : ConsentLaunch
}
