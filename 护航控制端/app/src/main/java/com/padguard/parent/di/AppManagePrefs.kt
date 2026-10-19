package com.padguard.parent.di

import android.app.Application
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用管理页的本地界面偏好（落 SharedPreferences，跨页面 / 重启都生效）。
 *
 * 为什么不能放 ViewModel 内存里：「是否显示系统应用」此前只存在
 * AppManageViewModel 的 StateFlow，而 ViewModel 跟随导航返回栈销毁——
 * 家长勾选后返回首页再进应用管理，勾选就被悄悄重置，表现为"筛选失灵"。
 * 这类纯视图偏好与服务端无关，落本地一份即可。
 */
@Singleton
class AppManagePrefs @Inject constructor(
    private val application: Application
) {
    private val prefs: SharedPreferences =
        application.getSharedPreferences("padguard_parent_ui", android.content.Context.MODE_PRIVATE)

    private val _showSystemApps = MutableStateFlow(readShowSystemApps())
    val showSystemApps: StateFlow<Boolean> = _showSystemApps.asStateFlow()

    private fun readShowSystemApps(): Boolean = prefs.getBoolean(KEY_SHOW_SYSTEM_APPS, false)

    fun getShowSystemApps(): Boolean = _showSystemApps.value

    fun setShowSystemApps(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_SYSTEM_APPS, show).apply()
        _showSystemApps.value = show
    }

    companion object {
        private const val KEY_SHOW_SYSTEM_APPS = "app_manage_show_system_apps"
    }
}
