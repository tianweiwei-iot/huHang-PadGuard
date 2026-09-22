package com.padguard.parent.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.domain.model.User
import com.padguard.parent.data.auth.TokenManager
import com.padguard.presentation.ui.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 会话门控（启动路由决策 + 全局登录态感知）。
 *
 * 修复「杀进程/重装后总是回到登录页」的问题：
 * TokenManager 一直把会话持久化在 DataStore 里，但导航图的 startDestination
 * 此前硬编码为登录页，持久化数据从未被消费。现在由本类在启动时读取一次，
 * 已登录则直接进入主页，未登录才进入登录页。
 *
 * 同时把 DataStore 的用户流转为响应式 StateFlow：会话在任何时刻被清空
 * （如 TokenAuthenticator 刷新失败后 clear），导航层据此自动退回登录页，
 * 避免"令牌已失效但界面停留在主页"的假登录态。
 *
 * 启动判定只读本地存储，不发网络请求——服务端不可达不应阻塞进入主页，
 * 令牌过期由 OkHttp Authenticator 的静默续期兜底。
 */
@HiltViewModel
class SessionViewModel @Inject constructor(
    tokenManager: TokenManager
) : ViewModel() {

    /** 持久化会话对应的用户；null 表示无登录态（含会话被清空的瞬间） */
    val user: StateFlow<User?> = tokenManager.currentUserFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    /**
     * 启动起点路由；null 表示会话读取尚未完成（首帧显示启动占位，避免闪登录页）。
     * 仅在首次组合前解析一次，之后保持恒定——Navigation 的 startDestination 不允许中途变更。
     */
    val bootResolved: StateFlow<Boolean> = tokenManager.isLoggedInFlow()
        .map { true }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

    companion object {
        /** 未登录时的起点 */
        val GUEST_START: String = Screen.Login.route

        /** 已登录时的起点 */
        val MEMBER_START: String = Screen.Main.route
    }
}
