package com.padguard.parent.di

import android.app.Application
import android.content.SharedPreferences
import com.padguard.parent.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 家长端服务器地址的运行时配置。
 *
 * 为什么做成可变的运行时配置而不是写死 BuildConfig：
 * 实测中手机连不上电脑通常是「路由器把无线设备隔离」导致，与 App 无关；
 * 用 USB 数据线直连电脑后，电脑会拿到一个全新的 IP（如 192.168.42.1），
 * 写死的 192.168.1.10 就失效了。把地址开放成手动填写，换任何网络都不用重打包。
 *
 * 默认值取 [BuildConfig.BASE_URL]，用户首次填写后落 SharedPreferences，重启后依然生效。
 * 配合 [com.padguard.parent.data.remote.HostSelectionInterceptor] 实现「改完立即生效、无需重启 App」。
 */
@Singleton
class ServerPrefs @Inject constructor(
    private val application: Application
) {
    private val prefs: SharedPreferences =
        application.getSharedPreferences("padguard_parent_server", android.content.Context.MODE_PRIVATE)

    private val _baseUrl = MutableStateFlow(read())
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private fun read(): String {
        val saved = prefs.getString(KEY_BASE_URL, null)
        return if (saved.isNullOrBlank()) BuildConfig.BASE_URL else saved
    }

    fun getBaseUrl(): String = _baseUrl.value

    fun setBaseUrl(url: String) {
        val normalized = url.trim().trimEnd('/') + "/"
        prefs.edit().putString(KEY_BASE_URL, normalized).apply()
        _baseUrl.value = normalized
    }

    companion object {
        private const val KEY_BASE_URL = "base_url"
    }
}
