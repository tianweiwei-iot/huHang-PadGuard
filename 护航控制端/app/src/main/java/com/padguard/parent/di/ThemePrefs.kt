package com.padguard.parent.di

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.padguard.parent.presentation.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 主题模式持久化（外观切换）。
 *
 * 采用与 [ServerPrefs] 一致的成熟模式：SharedPreferences 落盘 + StateFlow 暴露运行时状态，
 * 由 Hilt 单例持有。默认 [ThemeMode.MINT]（方案 05 青空薄荷），用户切换后落盘，重启保持。
 *
 * 设计约束：
 * - 仅保存主题的 [ThemeMode.id] 字符串，枚举端通过 [ThemeMode.fromId] 映射；
 *   已上线取值（mint/bluegray/cream）禁止改动，否则旧版已存值无法识别会回落默认。
 * - 对外只暴露不可变的 [themeMode] 流与 [setThemeMode]，写入即更新流，根层收集后触发整树重组。
 */
@Singleton
class ThemePrefs @Inject constructor(
    private val application: Application
) {
    private val prefs: SharedPreferences =
        application.getSharedPreferences("padguard_parent_theme", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(read())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private fun read(): ThemeMode =
        ThemeMode.fromId(prefs.getString(KEY_THEME_MODE, null))

    fun getThemeMode(): ThemeMode = _themeMode.value

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.id).apply()
        _themeMode.value = mode
    }

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
    }
}
