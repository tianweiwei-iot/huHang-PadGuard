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

    /**
     * 密码闸门本体，供桌面**就地**弹出应用门禁对话框使用。
     *
     * 之所以直接暴露它而不是再包一层：校验与限次逻辑必须只有一份实现，
     * 桌面弹框与无障碍浮层用的是同一个 [GuardPasswordManager]，
     * 任何中间封装都会让两条路径的口径悄悄分叉。
     */
    val passwordManager: GuardPasswordManager get() = guard

    /** 校验家长密码，用于进入管理界面（带失败限次） */
    suspend fun verifyParent(plain: String) = guard.verifyWithLimit(plain)

    /** 该包是否属于「家长已取消授权」（用于门禁文案如实区分授权取消与仅需验证）。 */
    fun isRevoked(pkg: String): Boolean = appGuard.isHiddenControlled(pkg)

    /**
     * 门禁验证通过后打开应用：**先落放行，再启动**。
     *
     * 顺序不可颠倒 —— 应用一启动，无障碍服务立刻就会收到该包的窗口事件；
     * 若放行尚未生效，孩子会看到"密码输对了，应用刚起来又被顶回桌面"。
     */
    fun openAfterGrant(pkg: String) {
        viewModelScope.launch {
            guard.grantAppAccess(pkg)
            launchApp(pkg)
        }
    }

    /** 拉取并过滤桌面应用。进入桌面与每次回到桌面都应调用。 */
    fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            // 回到自家桌面即意味着"上一次打开应用"已结束：作废那次放行，
            // 下次点同一应用必须重新验证。无障碍服务只盯非自家包的前台变化，
            // 自家桌面这一侧必须由这里补上，否则孩子回桌面后再点就能免密进入。
            runCatching { guard.revokeAppAccess() }
            val items = runCatching { buildAllowedApps() }.getOrElse { emptyList() }
            // 二级兜底：万一上面仍异常，退化为显示全部已安装应用，绝不空白
            val safe = if (items.isEmpty()) fallbackAllApps() else items
            _apps.clear()
            _apps.addAll(safe)
        }
    }

    /** 按策略过滤后的桌面应用列表（权威入口：取消授权/名单外应用直接在桌面不出现） */
    private fun buildAllowedApps(): List<LauncherAppItem> {
        val pm = context.packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val ris = pm.queryIntentActivities(launchIntent, 0)
        val result = ArrayList<LauncherAppItem>()
        for (ri in ris) {
            val pkg = ri.activityInfo.packageName
            if (pkg == context.packageName) continue // 不在桌面显示自家管理 App
            // 权威入口判定：由 AppGuardDecisions.isLaunchableByChild 统一口径，
            // 取消授权 / 名单外的应用直接在桌面不出现，从源头减少触达与被最近任务绕过的机会。
            // 未下发策略时 isLaunchableByChild 返回 true（fail-open），保证桌面不空白、设备可用。
            if (!appGuard.isLaunchableByChild(pkg)) continue
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
     * 打开该应用前是否需要门禁（孩子桌面点开应用的闸门）。
     *
     * 判定口径完全收敛到 [AppGuardDecisions.needsLaunchGuard]，
     * 与无障碍软拦截（[com.padguard.child.guard.OperationGuardAccessibilityService]）共用
     * **同一份纯函数**，确保「桌面点开」与「最近任务 / 通知点开」行为一致，
     * 不会一边免密一边要密，也不会因为「未设家长密码」而静默放行
     * （取消授权的应用在任何情况下都走门禁；此前的"未设密码即放行"正是管控失效的成因之一）。
     */
    fun needsLaunchGuard(pkg: String): Boolean =
        appGuard.decisions()?.needsLaunchGuard(
            pkg = pkg,
            appAccessGranted = guard.isAppAccessGrantedNow(pkg),
            unlocked = guard.isUnlockedNow()
        ) ?: false
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
