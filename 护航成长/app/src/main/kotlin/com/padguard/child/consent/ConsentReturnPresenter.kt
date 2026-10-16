package com.padguard.child.consent

import android.content.Context
import android.content.Intent
import com.padguard.child.permission.SpecialPermission
import com.padguard.core.common.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 授权自动回前台协调器（「免手动返回」的统一入口）。
 *
 * ## 职责收敛
 * 只负责「启动 / 停止」[ConsentReturnService]，不再自己轮询、也不再发通知。
 * 真正的轮询与后台 `startActivity` 交给前台服务——这是 Android 10+ 下
 * 「后台把 App 重新顶回前台」唯一可靠的豁免路径（服务自前台态拉起后 startActivity 合法）。
 *
 * ## 为什么之前做不到真免返回（用户反馈「每个授权都要手动返回」的根因）
 * 旧实现发的是一条 full-screen intent 通知：平板处于解锁使用态时，全屏 Intent 会降级为
 * 需手动点按的悬浮通知，等于把「点继续」换成了「点通知」——家长仍要手动操作；
 * 后台直接 `startActivity` 又被 Android 10+ 静默拦截，于是只能按系统返回键才生效。
 * 这两点叠加，表现就是「授权完必须手动返回」。本类把职责转交前台服务后，从根上消除该问题。
 */
@Singleton
class ConsentReturnPresenter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notifier: ConsentReturnNotifier
) {

    /**
     * 盯着一组 [SpecialPermission] 目标：启动前台服务轮询，任一项被授予即自动把授权页拉回前台。
     */
    fun watch(targets: Set<SpecialPermission>) {
        if (targets.isEmpty()) return
        startService(targets.map { it.key })
    }

    /**
     * 盯着一组自定义 key（如 [KEY_DEVICE_ADMIN]）：与 [watch] 同源，
     * 覆盖「无系统回调、需轮询检测」的目标（典型是设备管理员激活）。
     */
    fun watch(keys: List<String>) {
        if (keys.isEmpty()) return
        startService(keys)
    }

    private fun startService(keys: List<String>) {
        stop()
        runCatching {
            context.startForegroundService(
                Intent(context, ConsentReturnService::class.java).apply {
                    putStringArrayListExtra(ConsentReturnService.EXTRA_KEYS, ArrayList(keys))
                }
            )
        }.onFailure { Logger.e(TAG) { "start consent return service failed: ${it.message}" } }
    }

    /** 停止盯守（授权页已回前台 / 流程结束 / 切到非设置步骤时调用）。 */
    fun stop() {
        runCatching { context.stopService(Intent(context, ConsentReturnService::class.java)) }
            .onFailure { Logger.w(TAG) { "stop consent return service failed: ${it.message}" } }
    }

    /** 撤掉回前台通知（授权页已可见时调用，避免通知栏留一条过期提醒）。 */
    fun cancelNotification() = notifier.cancel()

    companion object {
        const val TAG = "ConsentReturnPresenter"
        /** 设备管理员（DA）激活的盯守 key，与 [ConsentReturnService] 约定一致 */
        const val KEY_DEVICE_ADMIN = "deviceAdmin"
    }
}
