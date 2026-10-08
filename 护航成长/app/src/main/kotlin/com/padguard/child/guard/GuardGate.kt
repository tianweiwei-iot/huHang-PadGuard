package com.padguard.child.guard

import android.content.Context

/**
 * App 内操作闸门（硬控）。
 *
 * 与无障碍服务的「系统级软拦截」互补：当孩子端 App **自己**要发起会改动平板的动作
 * （例如权限引导里点「去设置」、未来上线的「应用管理」入口）时，先调用 [ensureUnlocked]，
 * 未解锁就拉起密码框并阻止原动作；解锁窗口内则直接放行。
 *
 * 这样无论改动来自「孩子手动进系统设置」（无障碍拦）还是「App 内按钮」（此处拦），
 * 都统一走同一把密码锁，口径一致。
 */
object GuardGate {

    /**
     * 继续前的闸门检查。
     * @return `true` 表示允许继续（密码未启用、或处于解锁窗口内）；
     *         `false` 表示已拉起解锁界面、原动作应被中止。
     */
    fun ensureUnlocked(context: Context, manager: GuardPasswordManager): Boolean {
        if (!manager.isEnabledNow()) return true
        if (manager.isUnlockedNow()) return true
        manager.launchUnlock(context)
        return false
    }
}
