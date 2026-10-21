package com.padguard.core.engine.guard

import android.content.Context
import android.content.SharedPreferences
import com.padguard.core.common.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 整机管控总开关（权威真相源）。
 *
 * ## 解决什么问题
 * 此前"应用管控"只是应用黑白名单（[com.padguard.core.data.model.policy.AppPolicy]），
 * 并不等于"设备释放"。锁屏浮层、无障碍对设置/应用的拦截、DO 下的权限回收与 Kiosk 钉死，
 * 都由各自的锁定态或"服务是否开启"驱动，与名单无关。于是家长端"取消应用管控"只清空名单，
 * 设备依旧被锁、依旧进不了设置——这正是"取消应用管控后仍无法进设置"的直接成因：
 * 根本没有"释放整机"的开关。
 *
 * 本开关补上这个缺失的总闸：
 * - `active = true`：管控生效（默认，完全兼容旧行为）；
 * - `active = false`：整机暂停管控，孩子端锁屏浮层不铺、无障碍拦截全部解除、DO 下不回收权限、
 *   不钉 Kiosk，设备回到完全可用状态，家长/操作员可自由进入系统设置。
 *
 * 设备是否"应该被管控"由本开关唯一决定，所有拦截/锁定入口都来这里问询，杜绝
 * "名单空了但设备还锁着"的割裂。值持久化落盘：进程重启/设备重启后不丢失，
 * 下次策略刷新前保持家长最后设定的意图。
 */
@Singleton
class GuardMasterSwitch @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _active = MutableStateFlow(prefs.getBoolean(KEY_ACTIVE, DEFAULT_ACTIVE))
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** 设定总开关。持久化 + 广播给所有订阅者（无障碍服务、锁屏呈现、DO 执行器）。 */
    fun setActive(value: Boolean) {
        prefs.edit().putBoolean(KEY_ACTIVE, value).apply()
        if (_active.value != value) {
            _active.value = value
        }
        Logger.i(TAG) { "management active -> $value" }
    }

    companion object {
        private const val TAG = "GuardMasterSwitch"
        private const val PREFS_NAME = "padguard_guard_master"
        private const val KEY_ACTIVE = "active"
        const val DEFAULT_ACTIVE = true
    }
}
