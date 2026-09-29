package com.padguard.child.monitor

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import com.padguard.core.common.Logger
import com.padguard.core.engine.admin.DeviceAdminBridge
import com.padguard.core.transport.http.AppInventoryItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 已安装应用台账采集（应用监控 / 远程运维的数据源）。
 *
 * ## 为什么不再依赖 QUERY_ALL_PACKAGES
 * Android 11+ 限制包可见性：默认只能看到自己和系统级可见的少量应用。
 * 两种合规做法：① 声明 `QUERY_ALL_PACKAGES`（应用市场上架会被重点审核）；
 * ② 在 manifest 里用 `<queries>` 声明用途。
 *
 * 本项目**不需要**看到全部应用：管控真正关心的是"孩子能打开的、带启动器的应用"，
 * 系统后台服务、无图标组件对家长毫无意义且会把列表撑到几百条不可用。
 *
 * ## 为什么改用"反查启动器意图"而不是 `getInstalledPackages`
 * 实测（Android 14，250 个已装包）：带 `<queries>` 声明的情况下
 * `getInstalledPackages` 仍只返回自身 + 极少数系统包，拿不到桌面上那几十个应用，
 * 台账最终只剩 1 条 —— 家长端看到的是一份几乎空白的应用列表。
 * 而 `queryIntentActivities(MAIN/LAUNCHER)` 走的是意图解析路径，
 * 正是 `<queries>` 明确放行的通道，能稳定拿回全部有启动入口的应用。
 * 采集语义也完全一致：这两条路径筛出来的本来就是同一批"孩子能打开的应用"。
 *
 * ## 被隐藏的应用
 * 隐藏后应用从启动器消失，意图反查自然也查不到它。
 * 这类包由 [DeviceAdminBridge.hiddenPackages] 本地记账补回，
 * 否则家长一关权限，这个应用就从列表里凭空消失、再也找不回来。
 *
 * ## 性能
 * 意图解析与图标编码都是跨进程 + 解码操作，上百个包时是百毫秒级，
 * 绝不能在主线程做（否则界面掉帧）。
 * 这里整体包在 [Dispatchers.IO] 里，并且由调用方控制频率（默认 30 分钟一次）。
 */
@Singleton
class AppInventoryCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val admin: DeviceAdminBridge
) {

    /** 采集全量台账。@return 已过滤后的应用列表（无启动入口的系统组件已被剔除） */
    suspend fun collect(): List<AppInventoryItem> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        // pkg -> 是否有启动入口（false 表示是被我们隐藏、只在记账里的包）
        val candidates = LinkedHashMap<String, Boolean>(128)
        for (category in LAUNCHER_CATEGORIES) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            val resolved = runCatching { pm.queryIntentActivities(intent, 0) }.getOrElse {
                Logger.w(TAG) { "query launcher activities failed: ${it.message}" }
                emptyList()
            }
            for (ri in resolved) {
                val pkg = ri.activityInfo?.packageName ?: continue
                // 已记录的包若又出现在启动器里，说明它已被取消隐藏
                candidates[pkg] = true
            }
        }
        for (pkg in runCatching { admin.hiddenPackages() }.getOrDefault(emptySet())) {
            if (!candidates.containsKey(pkg)) candidates[pkg] = false
        }

        val items = ArrayList<AppInventoryItem>(candidates.size)
        for ((pkg, hasLauncher) in candidates) {
            // 单个包取信息失败（已被卸载 / ROM 限制）不该让整份台账报废
            val info = runCatching { packageInfo(pm, pkg) }.getOrNull()
            if (info == null && hasLauncher) continue
            val app = info?.applicationInfo
            val isSystem = app != null && (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            items.add(
                AppInventoryItem(
                    packageName = pkg,
                    appName = app?.let { pm.getApplicationLabel(it).toString() } ?: pkg,
                    versionName = info?.versionName,
                    versionCode = info?.let { versionCode(it) } ?: 0L,
                    isSystem = isSystem,
                    installTime = info?.firstInstallTime?.takeIf { it > 0 },
                    updateTime = info?.lastUpdateTime?.takeIf { it > 0 },
                    iconBase64 = app?.let { iconBase64(pm, it) },
                    hidden = !hasLauncher
                )
            )
        }
        Logger.d(TAG) { "app inventory collected: ${items.size}" }
        items
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(pm: PackageManager, pkg: String): PackageInfo? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0L))
            } else {
                pm.getPackageInfo(pkg, 0)
            }
        }.onFailure {
            Logger.w(TAG) { "package info unavailable for $pkg: ${it.message}" }
        }.getOrNull()

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()

    /**
     * 把应用图标压成 48dp 的 PNG 并 Base64。
     *
     * 三个必要的取舍：
     * - **必须压缩**：原图可能是 192dp 甚至矢量自适应图标，直接 Base64 一个就能上百 KB；
     * - **必须在 IO 线程**：`getApplicationIcon` 涉及跨进程包管理与解码，主线程调用会掉帧，
     *   而本函数被 [collect] 调用，后者整体已在 [Dispatchers.IO] 中；
     * - **必须容忍失败**：个别 ROM 的 AdaptiveIcon 在解码时抛异常，
     *   一个图标取不到不能让整份台账报废，返回 null 让服务端沿用旧图标即可。
     */
    private fun iconBase64(pm: PackageManager, app: ApplicationInfo): String? = runCatching {
        val drawable = pm.getApplicationIcon(app)
        // 按设备密度换算成像素：48dp 在 xhdpi 上就是 96px，保证各机型清晰度一致
        val size = (ICON_SIZE_DP * context.resources.displayMetrics.density)
            .coerceAtLeast(1f).toInt().coerceAtMost(MAX_ICON_PX)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        val buffer = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer)
        bitmap.recycle()
        android.util.Base64.encodeToString(buffer.toByteArray(), android.util.Base64.NO_WRAP)
    }.onFailure {
        Logger.w(TAG) { "icon encode failed for ${app.packageName}: ${it.message}" }
    }.getOrNull()

    companion object {
        private const val TAG = "AppInventoryCollector"
        /** 图标目标尺寸（dp）：够家长辨认即可，再大只是白白增加上报体积 */
        private const val ICON_SIZE_DP = 48
        /** 像素上限：极端密度（如平板 3.5x）下防止单图标过大 */
        private const val MAX_ICON_PX = 192
        /** 桌面入口的两个 category：平板/电视的桌面走 LEANBACK，只查 LAUNCHER 会漏掉一整类 */
        private val LAUNCHER_CATEGORIES = listOf(
            Intent.CATEGORY_LAUNCHER,
            Intent.CATEGORY_LEANBACK_LAUNCHER
        )
    }
}
