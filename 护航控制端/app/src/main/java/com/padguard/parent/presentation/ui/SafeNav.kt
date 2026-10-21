package com.padguard.parent.presentation.ui

import androidx.navigation.NavController

/**
 * 安全返回：仅在当前目的地不是导航图起始目的地时才弹出返回栈。
 *
 * ## 根因（白屏崩溃）
 * 子页（设备详情 / 管控策略 / 实时监控 / 消息中心 等）的返回箭头直接调用
 * [NavController.popBackStack]。该直接调用**不会**像系统返回键那样保护起始页：
 * 在快速连点返回箭头时（退出转场动画期间按钮仍可被点击），会把根导航的起始页（首页）
 * 一并弹出，导致返回栈被打空、[NavController.currentDestination] 变为 null，
 * NavHost 无当前目的地而渲染白屏；此时已无任何 UI 可导航，只能杀进程重启。
 *
 * ## 修复策略
 * 已处于起始目的地（首页）时直接返回 false，不再弹出 —— 既消除白屏，又保证"返回首页"语义：
 * 连点返回最终稳定停在首页，而不是越过首页把整条栈清空。
 *
 * 正常的"子页 → 首页"单次返回依旧照常执行，本函数只拦截"误删起始页"这一条崩溃路径。
 */
fun NavController.safePopBackStack(): Boolean {
    val current = currentDestination ?: return false
    if (!shouldPopFrom(current.id, graph.startDestinationId)) return false
    return popBackStack()
}

/**
 * 纯判定逻辑（与 [NavController] 解耦，便于单元测试）：
 * 当前目的地为空、或与起始目的地相同（即已在首页）时禁止弹出返回栈。
 */
internal fun shouldPopFrom(currentDestinationId: Int?, startDestinationId: Int): Boolean {
    if (currentDestinationId == null) return false
    if (currentDestinationId == startDestinationId) return false
    return true
}
