package com.padguard.child

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.child.guard.AppGuardPolicyStore
import com.padguard.child.guard.GuardPasswordManager
import com.padguard.core.data.model.policy.AppListMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 孩子桌面（Launcher）的视图模型。
 *
 * ## 职责定位
 * 这是"应用管控"的**可视网关**：桌面只渲染家长允许的 App，孩子看不到、点不到违禁应用。
 * 它不替代无障碍服务的硬拦截（[com.padguard.child.guard.OperationGuardAccessibilityService]）
 * —— 孩子若从最近任务 / 通知 / 其他桌面启动违禁应用，仍由无障碍兜底顶回。
 * 两者叠加才是闭环。
 *
 * ## 防砖是底线
 * 任何异常（策略读不出、图标加载失败、包查询异常）都降级为"显示全部已安装应用"，
 * 绝不允许因为管控逻辑出错而导致桌面空白、设备无法使用。
 */
data class LauncherAppItem(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap
)

@HiltViewModel
class KidHomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appGuard: AppGuardPolicyStore,
    private val guard: GuardPasswordManager
) : ViewModel() {

    private val _apps = androidx.compose.runtime.mutableStateListOf<LauncherAppItem>()
    val apps: List<LauncherAppItem> get() = _apps

    /** 家长入口是否需要密码（未启用操作密码时直接放行） */
    suspend fun isGuardEnabled(): Boolean = guard.isEnabled()

    /** 校验家长密码，用于进入管理界面（带失败限次） */
    suspend fun verifyParent(plain: String) = guard.verifyWithLimit(plain)

    /** 拉取并过滤桌面应用。进入桌面与每次回到桌面都应调用。 */
    fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            val items = runCatching { buildAllowedApps() }.getOrElse { emptyList() }
            // 二级兜底：万一上面仍异常，退化为显示全部已安装应用，绝不空白
            val safe = if (items.isEmpty()) fallbackAllApps() else items
            _apps.clear()
            _apps.addAll(safe)
        }
    }

    /** 按策略过滤后的桌面应用列表 */
    private fun buildAllowedApps(): List<LauncherAppItem> {
        val pm = context.packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val ris = pm.queryIntentActivities(launchIntent, 0)
        val snap = appGuard.snapshot()
        val result = ArrayList<LauncherAppItem>()
        for (ri in ris) {
            val pkg = ri.activityInfo.packageName
            if (pkg == context.packageName) continue // 不在桌面显示自家管理 App
            val visible = when {
                snap == null -> true // 未下发策略：全部可见，设备保持可用
                snap.mode == AppListMode.WHITELIST -> pkg in snap.whitelist
                else -> pkg !in snap.blacklist // BLACKLIST：隐藏黑名单
            }
            if (!visible) continue
            val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)
            val icon = runCatching { ri.loadIcon(pm).toImageBitmap() }.getOrNull() ?: continue
            result.add(LauncherAppItem(pkg, label, icon))
        }
        result.sortBy { it.label.lowercase() }
        return result
    }

    /** 异常兜底的"显示全部"实现 */
    private fun fallbackAllApps(): List<LauncherAppItem> {
        val pm = context.packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching {
            pm.queryIntentActivities(launchIntent, 0).mapNotNull { ri ->
                val pkg = ri.activityInfo.packageName
                if (pkg == context.packageName) return@mapNotNull null
                val icon = runCatching { ri.loadIcon(pm).toImageBitmap() }.getOrNull() ?: return@mapNotNull null
                LauncherAppItem(
                    pkg,
                    runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg),
                    icon
                )
            }.sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
    }

    /** 启动指定应用；返回是否成功拉起 */
    fun launchApp(pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /**
     * 打开该应用前是否需要管理密码（孩子桌面点开受管控应用的闸门）。
     *
     * 口径与 [com.padguard.child.guard.OperationGuardAccessibilityService.decide] 完全一致，
     * 确保「桌面点开」与「最近任务/通知点开」（无障碍兜底）行为统一，不会一边免密一边要密：
     * - 操作密码未启用 → 不拦（没密码可验）；
     * - 处于解锁窗口内 → 放行；
     * - 家长未开启「打开应用也要密码」([AppGuardPolicyStore.launchBlockEnabled]) → 不拦；
     * - 系统关键包（防砖名单）→ 不拦；
     * - 其余命中管控名单的应用 → 必须先输密码。
     *
     * 这是桌面这层"可视网关"的必要补强：此前 [launchApp] 直接启动，会绕过本应由
     * 无障碍服务实施的启动密码闸门（且一旦无障碍未开就彻底免密）。
     */
    fun needsLaunchGuard(pkg: String): Boolean {
        if (!guard.isEnabledNow()) return false
        if (guard.isUnlockedNow()) return false
        if (!appGuard.launchBlockEnabled()) return false
        if (appGuard.isSystemCritical(pkg)) return false
        return appGuard.isControlled(pkg)
    }
}

private fun Drawable.toImageBitmap(): ImageBitmap {
    val w = maxOf(intrinsicWidth, 1)
    val h = maxOf(intrinsicHeight, 1)
    val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bm)
    setBounds(0, 0, w, h)
    draw(canvas)
    return bm.asImageBitmap()
}
