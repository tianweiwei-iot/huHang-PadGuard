package com.padguard.child.guard

import android.os.UserManager
import com.padguard.core.common.Logger
import com.padguard.core.engine.admin.DeviceAdminBridge
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自身常驻加固基线（永驻后台 + 进程不可被杀 + 关闭/卸载需家长密码 的治本落地）。
 *
 * ## 解决什么
 * 之前，防强行停止(`DISALLOW_APPS_CONTROL`)、防卸载(`setUninstallBlocked`)、防恢复出厂/安全模式
 * 这些"治本级"加固，只在 [com.padguard.core.engine.enforcer.SecurityEnforcer] 随**服务端策略下发**时应用，
 * 依赖联网与绑定。后果：设备离线开机、或策略尚未拉到的窗口里，设置里的"强行停止"按钮仍是亮的，
 * 孩子可以先把管控杀掉再断网 —— 这与"进程无法被结束、关闭卸载需家长密码"的承诺相悖。
 *
 * 本模块把这些加固收敛为**离线即生效的基线**：只要本机持有 Device Owner / Profile Owner，
 * 开机、守护服务每次重建、拿到 DO/DA 的那一刻就落地，不依赖服务端策略，并周期自愈。
 *
 * ## 与 [com.padguard.core.engine.enforcer.SecurityEnforcer] 的关系
 * 本模块是"离线基线"，SecurityEnforcer 是"在线策略对齐"。两者施加同一批限制、幂等互不冲突：
 * 在线时由策略层按策略再压一次（保证被 ROM 重置的限制能被压回），离线/开机窗口由本模块兜底。
 *
 * ## 为什么不动 `DISALLOW_UNINSTALL_APPS`
 * 卸载闸门走 `setUninstallBlocked(self,true)`，家长卸载流程([com.padguard.child.hidden.ExitController])
 * 会先解封再拉卸载页，闭环清晰；而 `DISALLOW_UNINSTALL_APPS` 会连同 DPC 自身的卸载意图一并拦截，
 * 反而堵死家长的正规卸载路径，故不在此设（该限制仍由 SecurityEnforcer 按策略条件施加）。
 *
 * ## 非 DO/PO 设备（含纯 DEVICE_ADMIN）
 * 这些限制全部要求 DO/PO 身份；[com.padguard.core.engine.admin.DeviceAdminBridge.hasOwnerPrivileges]
 * 为 false 时本方法直接返回 false、零副作用。此时"进程防杀/防卸载"降级为：
 * 双进程看门狗 + JobScheduler + 闹钟 + 开机广播 自愈拉活 + 无障碍软门拦截卸载，
 * 这是 Android 安全模型下非 DO 设备的上限（已在各注释中如实标注）。
 */
@Singleton
class SelfProtectionModule @Inject constructor(
    private val admin: DeviceAdminBridge
) {

    /**
     * 离线基线：把自身常驻加固一次性落地（幂等，可安全在任何时机调用）。
     *
     * @return true 表示本机持有 DO/PO 且已施加加固；false 表示非 DO/PO（已自动降级）。
     */
    fun applyBaseline(): Boolean {
        // 非 DO/PO：Android 不允许施加这些限制，直接降级返回，绝不抛异常、绝不静默失效不报。
        if (!admin.hasOwnerPrivileges()) return false

        // 1) 系统级封死自身卸载（治本）。家长卸载流程会先解封再拉卸载页。
        admin.setUninstallBlocked(admin.selfPackageName, true)
        // 2) "强行停止 / 清除数据"置灰（进程无法被孩子结束的治本手段，仅 DO/PO 有效）。
        admin.setUserRestriction(UserManager.DISALLOW_APPS_CONTROL, true)
        // 3) 防恢复出厂 / 安全模式：封住从底层绕过管控的入口。
        admin.setUserRestriction(UserManager.DISALLOW_FACTORY_RESET, true)
        admin.setUserRestriction(UserManager.DISALLOW_SAFE_BOOT, true)
        return true
    }

    companion object {
        private const val TAG = "SelfProtection"
    }
}
